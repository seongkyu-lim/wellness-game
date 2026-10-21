import Foundation
import OSLog

/// 마지막으로 오늘 데이터 동기화에 성공한 시각. 포그라운드·백그라운드 어느 경로든 성공하면 기록한다.
/// 다음 동기화 때 전날 데이터를 다시 보낼지 판단하는 데 쓴다(`SyncDayPlanner`).
struct HealthSyncHistory {
    static let lastSuccessKey = "healthSync.lastSuccessAt"

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var lastSuccessAt: Date? {
        defaults.object(forKey: Self.lastSuccessKey) as? Date
    }

    func recordSuccess(at date: Date) {
        defaults.set(date, forKey: Self.lastSuccessKey)
    }
}

/// 이번 동기화에서 보낼 날짜를 정한다.
enum SyncDayPlanner {
    /// 마지막 성공이 오늘보다 이전 날(기기 달력 기준)이면 전날 데이터도 다시 보낸다.
    ///
    /// 앱을 열지 않은 채 하루가 지나면 전날 마지막 동기화 이후의 걸음·운동·수면이 서버에 가지 않는다.
    /// 서버는 같은 데이터를 다시 받아도 중복을 걸러 늘어난 몫만 반영하므로 전날을 통째로 다시 보낸다.
    /// 서버는 요청 날짜가 서버 기준 오늘 ±1일일 때만 받으므로 그보다 이전 날은 보내지 않는다.
    /// 성공 기록이 없거나(첫 동기화) 미래 시각이면(기기 시간 변경) 오늘만 보낸다.
    static func shouldResendPreviousDay(lastSuccessAt: Date?, now: Date, calendar: Calendar) -> Bool {
        guard let lastSuccessAt, lastSuccessAt <= now else {
            return false
        }
        return calendar.startOfDay(for: lastSuccessAt) < calendar.startOfDay(for: now)
    }

    /// 전날 0시. 달력 계산이 실패하면 nil.
    static func previousDay(of now: Date, calendar: Calendar) -> Date? {
        calendar.date(byAdding: .day, value: -1, to: calendar.startOfDay(for: now))
    }
}

/// 한 번의 동기화 결과(오늘 데이터 기준).
struct HealthSyncResult {
    let snapshot: DailyHealthSnapshot
    let response: HealthActivitySyncResponse
}

/// 건강 데이터 → 서버 동기화 절차. 포그라운드(`DashboardViewModel`)와 백그라운드(`HealthBackgroundSync`)가
/// 함께 쓰며 화면 상태는 건드리지 않는다.
///
/// - 전날 재전송이 필요하면 전날 요청을 먼저 보내고 오늘 요청을 보낸다(각각 별도 요청).
/// - 전날 요청이 401 외의 이유로 실패하면(서버 날짜 범위, HealthKit 읽기 실패 등) 기록만 남기고 오늘 동기화를 계속한다.
///   오늘 동기화가 성공하면 성공 시각이 오늘로 바뀌므로 그 전날은 다시 보내지 않는다(최선 노력).
/// - 401은 호출한 쪽이 세션을 정리하도록 그대로 던진다.
@MainActor
final class HealthSyncService {
    let networkClient: NetworkClient
    let userSession: UserSession
    private let history: HealthSyncHistory
    private let calendar: Calendar
    private let now: () -> Date
    private let dateFormatter: DateFormatter

    init(
        networkClient: NetworkClient,
        userSession: UserSession,
        history: HealthSyncHistory = HealthSyncHistory(),
        calendar: Calendar = .current,
        now: @escaping () -> Date = Date.init
    ) {
        self.networkClient = networkClient
        self.userSession = userSession
        self.history = history
        self.calendar = calendar
        self.now = now
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        dateFormatter = formatter
    }

    /// Watch 조회 토큰이 만료 1일 전이면 다시 발급해 보낸다. 로그인하지 않았으면 아무것도 하지 않는다.
    func refreshWatchTokenIfNeeded() async {
        guard userSession.isSignedIn else { return }
        await userSession.refreshWatchTokenIfNeeded { [networkClient] in
            try await networkClient.issueWatchToken()
        }
    }

    /// (필요하면 전날 →) 오늘 순서로 읽어서 보낸다.
    /// - Parameter todayLoaded: 오늘 데이터를 읽은 직후, 서버 요청 전에 호출된다(화면에 먼저 보여 줄 때 쓴다).
    /// - Returns: 오늘 동기화 결과. 진행 중 로그아웃·계정 전환이 일어났으면 nil.
    func sync(
        provider: HealthDataProvider,
        userId: String,
        todayLoaded: (DailyHealthSnapshot) -> Void = { _ in }
    ) async throws -> HealthSyncResult? {
        let startedAt = now()
        if SyncDayPlanner.shouldResendPreviousDay(lastSuccessAt: history.lastSuccessAt, now: startedAt, calendar: calendar),
           let previousDay = SyncDayPlanner.previousDay(of: startedAt, calendar: calendar) {
            do {
                let snapshot = try await provider.load(day: previousDay)
                guard userSession.userId == userId else { return nil }
                _ = try await networkClient.sync(request(for: snapshot, userId: userId))
            } catch {
                if case NetworkError.unauthorized = error { throw error }
                Self.logger.info("전날 데이터 재전송 실패, 오늘 동기화는 계속합니다: \(error.localizedDescription, privacy: .public)")
            }
            guard userSession.userId == userId else { return nil }
        }

        let snapshot = try await provider.loadToday()
        guard userSession.userId == userId else { return nil }
        todayLoaded(snapshot)

        let response = try await networkClient.sync(request(for: snapshot, userId: userId))
        // 요청 중 로그아웃·계정 전환이 일어났으면 결과를 버린다.
        guard userSession.userId == userId else { return nil }
        history.recordSuccess(at: now())
        return HealthSyncResult(snapshot: snapshot, response: response)
    }

    private func request(for snapshot: DailyHealthSnapshot, userId: String) -> HealthActivitySyncRequest {
        HealthActivitySyncRequest(
            userId: userId,
            date: dateFormatter.string(from: snapshot.date),
            activities: snapshot.activities
        )
    }

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "HealthSync")
}
