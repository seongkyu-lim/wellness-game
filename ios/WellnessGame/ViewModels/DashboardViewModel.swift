import Foundation
import Combine

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published private(set) var snapshot: DailyHealthSnapshot?
    @Published private(set) var syncResponse: HealthActivitySyncResponse?
    @Published private(set) var isLoading = false
    @Published private(set) var statusMessage = String(localized: "건강 데이터를 자동으로 동기화합니다.")
    @Published var useMockData = false

    private let appleProvider: HealthDataProvider
    private let mockProvider: HealthDataProvider
    private let networkClient: NetworkClient
    private let userSession: UserSession
    private var lastSyncedAt: Date?

    init(
        appleProvider: HealthDataProvider = AppleHealthKitProvider(),
        mockProvider: HealthDataProvider = MockHealthDataProvider(),
        networkClient: NetworkClient = NetworkClient(),
        userSession: UserSession
    ) {
        self.appleProvider = appleProvider
        self.mockProvider = mockProvider
        self.networkClient = networkClient
        self.userSession = userSession
    }

    var activeProvider: HealthDataProvider {
        useMockData ? mockProvider : appleProvider
    }

    /// 권한 확인 → 오늘 데이터 로드 → 서버 동기화를 한 번에 수행한다.
    /// 앱 실행·포그라운드 복귀 시 호출되며, 잦은 복귀로 서버를 반복 호출하지 않도록
    /// 최소 간격(AutoSyncPolicy)을 지킨다. 당겨서 새로고침은 force로 간격을 무시한다.
    /// 로그인하지 않았으면 건강 데이터를 읽지도, 서버에 보내지도 않는다.
    func autoSync(force: Bool = false) async {
        guard let userId = userSession.userId else {
            statusMessage = userSession.reloginNotice ?? UserSession.signInPrompt
            return
        }
        guard force || AutoSyncPolicy.shouldSync(lastSyncedAt: lastSyncedAt, now: Date()) else {
            return
        }
        await perform {
            try await activeProvider.requestAuthorization()
            let loaded = try await activeProvider.loadToday()
            guard userSession.userId == userId else { return }
            snapshot = loaded

            let request = HealthActivitySyncRequest(
                userId: userId,
                date: Self.dateFormatter.string(from: loaded.date),
                activities: loaded.activities
            )
            let response = try await networkClient.sync(request)
            // 요청 중 로그아웃·계정 전환이 일어났으면 결과를 버린다.
            guard userSession.userId == userId else { return }
            syncResponse = response
            lastSyncedAt = Date()
            statusMessage = response.levelUp
                ? String(localized: "레벨업! 성장 결과를 확인하세요.")
                : String(localized: "자동 동기화 완료")
        }
    }

    /// 로그아웃·세션 만료 시 이전 사용자의 화면 데이터를 비운다.
    func reset() {
        snapshot = nil
        syncResponse = nil
        lastSyncedAt = nil
        statusMessage = userSession.reloginNotice ?? UserSession.signInPrompt
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    private func perform(_ operation: () async throws -> Void) async {
        isLoading = true
        defer { isLoading = false }
        let requestUserId = userSession.userId
        do {
            try await operation()
        } catch NetworkError.unauthorized {
            // 요청 도중 계정이 바뀌었다면 늦게 도착한 401로 새 세션을 지우지 않는다.
            guard userSession.userId == requestUserId else { return }
            userSession.handleUnauthorized()
            reset()
        } catch {
            statusMessage = String(localized: "동기화하지 못했어요. 당겨서 다시 시도할 수 있어요. (\(error.localizedDescription))")
        }
    }
}
