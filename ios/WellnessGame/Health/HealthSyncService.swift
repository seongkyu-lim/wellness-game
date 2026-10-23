import Foundation
import OSLog

private let logger = Logger(subsystem: "io.github.seongkyulim.wellnessgame", category: "HealthSync")

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
///
/// 날짜 기준이 두 가지다.
/// - 전날 재전송 여부와 서버가 받을 날짜인지는 서버와 같은 한국 시간(Asia/Seoul)으로 판단한다.
///   서버는 요청 날짜가 서버 기준 오늘 ±1일일 때만 받는다.
/// - HealthKit에서 읽는 하루 구간과 요청 날짜(`yyyy-MM-dd`)는 기기 달력을 쓴다. 사용자가 겪은 하루(자정~자정)를
///   그대로 보내야 걸음·운동이 엉뚱한 날짜로 묶이지 않기 때문이다. 기기 시간대가 한국이면 두 기준은 같다.
enum SyncDayPlanner {
    static let serverCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
        return calendar
    }()

    /// 마지막 성공이 서버 기준 오늘보다 이전 날이면 전날 데이터도 다시 보낸다.
    ///
    /// 앱을 열지 않은 채 하루가 지나면 전날 마지막 동기화 이후의 걸음·운동·수면이 서버에 가지 않는다.
    /// 서버는 같은 데이터를 다시 받아도 중복을 걸러 늘어난 몫만 반영하므로 전날을 통째로 다시 보낸다.
    /// 성공 기록이 없거나(첫 동기화) 미래 시각이면(기기 시간 변경) 오늘만 보낸다.
    static func shouldResendPreviousDay(
        lastSuccessAt: Date?,
        now: Date,
        serverCalendar: Calendar = SyncDayPlanner.serverCalendar
    ) -> Bool {
        guard let lastSuccessAt, lastSuccessAt <= now else {
            return false
        }
        return serverCalendar.startOfDay(for: lastSuccessAt) < serverCalendar.startOfDay(for: now)
    }

    /// 기기 달력 기준 전날 0시. 달력 계산이 실패하면 nil.
    static func previousDay(of now: Date, calendar: Calendar) -> Date? {
        calendar.date(byAdding: .day, value: -1, to: calendar.startOfDay(for: now))
    }

    /// 기기 달력의 `day` 날짜(`yyyy-MM-dd`)가 서버 기준 오늘 ±1일 안인지. 밖이면 서버가 거절하므로 보내지 않는다.
    static func isAcceptedByServer(
        day: Date,
        deviceCalendar: Calendar,
        now: Date,
        serverCalendar: Calendar = SyncDayPlanner.serverCalendar
    ) -> Bool {
        let components = deviceCalendar.dateComponents([.year, .month, .day], from: day)
        guard let serverDay = serverCalendar.date(from: components),
              let distance = serverCalendar.dateComponents(
                  [.day], from: serverCalendar.startOfDay(for: now), to: serverDay
              ).day else {
            return false
        }
        return abs(distance) <= 1
    }
}

/// 한 번의 동기화 결과(오늘 데이터 기준).
/// 진행 중인 동기화에 합류한 호출자는 `todayLoaded`를 받지 못하므로 `snapshot`으로 화면을 채운다.
struct HealthSyncResult {
    let snapshot: DailyHealthSnapshot
    let response: HealthActivitySyncResponse
}

/// 건강 데이터 → 서버 동기화 절차. 포그라운드(`DashboardViewModel`)와 백그라운드(`HealthBackgroundSync`)가
/// 함께 쓰며 화면 상태는 건드리지 않는다.
///
/// - 같은 사용자의 동기화가 진행 중이면 새로 보내지 않고 그 결과를 함께 기다린다(단일 비행).
///   앱을 열 때 관찰 쿼리의 첫 콜백과 포그라운드 자동 동기화가 겹쳐도 요청은 한 번만 나가고,
///   두 호출자 모두 같은 응답(레벨업 여부 포함)을 받는다. 합류하면 넘긴 `provider`는 쓰지 않는다.
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
    private let serverCalendar: Calendar
    private let now: () -> Date
    private let dateFormatter: DateFormatter
    private var inflight: (userId: String, task: Task<HealthSyncResult?, Error>)?

    /// - Parameter calendar: HealthKit 하루 구간과 요청 날짜에 쓰는 기기 달력.
    init(
        networkClient: NetworkClient,
        userSession: UserSession,
        history: HealthSyncHistory = HealthSyncHistory(),
        calendar: Calendar = .current,
        serverCalendar: Calendar = SyncDayPlanner.serverCalendar,
        now: @escaping () -> Date = Date.init
    ) {
        self.networkClient = networkClient
        self.userSession = userSession
        self.history = history
        self.calendar = calendar
        self.serverCalendar = serverCalendar
        self.now = now
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        dateFormatter = formatter
    }

    /// Watch 조회 토큰이 만료 1일 전이면 다시 발급해 보낸다. 로그인하지 않았으면 아무것도 하지 않는다.
    /// 동기화가 끝난 뒤에 부른다(백그라운드 시간이 모자라면 동기화가 먼저다).
    func refreshWatchTokenIfNeeded() async {
        guard userSession.isSignedIn else { return }
        await userSession.refreshWatchTokenIfNeeded { [networkClient] in
            try await networkClient.issueWatchToken()
        }
    }

    /// (필요하면 전날 →) 오늘 순서로 읽어서 보낸다.
    /// - Parameter todayLoaded: 오늘 데이터를 읽은 직후, 서버 요청 전에 호출된다(화면에 먼저 보여 줄 때 쓴다).
    ///   진행 중인 동기화에 합류하면 호출되지 않는다.
    /// - Returns: 오늘 동기화 결과. 진행 중 로그아웃·계정 전환이 일어났으면 nil.
    func sync(
        provider: HealthDataProvider,
        userId: String,
        todayLoaded: @escaping (DailyHealthSnapshot) -> Void = { _ in }
    ) async throws -> HealthSyncResult? {
        if let inflight, inflight.userId == userId {
            return try await inflight.task.value
        }
        let task = Task { [weak self] () async throws -> HealthSyncResult? in
            guard let self else { return nil }
            return try await self.performSync(provider: provider, userId: userId, todayLoaded: todayLoaded)
        }
        inflight = (userId, task)
        defer {
            if inflight?.task == task {
                inflight = nil
            }
        }
        return try await task.value
    }

    private func performSync(
        provider: HealthDataProvider,
        userId: String,
        todayLoaded: (DailyHealthSnapshot) -> Void
    ) async throws -> HealthSyncResult? {
        let startedAt = now()
        if SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: history.lastSuccessAt, now: startedAt, serverCalendar: serverCalendar
        ),
           let previousDay = SyncDayPlanner.previousDay(of: startedAt, calendar: calendar),
           SyncDayPlanner.isAcceptedByServer(
               day: previousDay, deviceCalendar: calendar, now: startedAt, serverCalendar: serverCalendar
           ) {
            do {
                let snapshot = try await provider.load(day: previousDay)
                guard userSession.userId == userId else { return nil }
                _ = try await networkClient.sync(request(for: snapshot, userId: userId))
            } catch {
                if case NetworkError.unauthorized = error { throw error }
                logger.info("전날 데이터 재전송 실패, 오늘 동기화는 계속합니다: \(error.localizedDescription, privacy: .public)")
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
}
