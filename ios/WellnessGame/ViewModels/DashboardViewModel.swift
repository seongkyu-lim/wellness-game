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
    private let userSession: UserSession
    private let syncService: HealthSyncService
    private let onHealthAuthorizationRequested: @MainActor () async -> Void
    private var lastSyncedAt: Date?

    /// - Parameters:
    ///   - syncService: 백그라운드 동기화와 같은 인스턴스를 넘긴다. nil이면 `networkClient`·`userSession`으로 만든다.
    ///   - onHealthAuthorizationRequested: HealthKit 권한 요청이 끝난 뒤 불린다(백그라운드 동기화 시작).
    init(
        appleProvider: HealthDataProvider = AppleHealthKitProvider(),
        mockProvider: HealthDataProvider = MockHealthDataProvider(),
        networkClient: NetworkClient = NetworkClient(),
        userSession: UserSession,
        syncService: HealthSyncService? = nil,
        onHealthAuthorizationRequested: @escaping @MainActor () async -> Void = {}
    ) {
        self.appleProvider = appleProvider
        self.mockProvider = mockProvider
        self.userSession = userSession
        self.syncService = syncService ?? HealthSyncService(networkClient: networkClient, userSession: userSession)
        self.onHealthAuthorizationRequested = onHealthAuthorizationRequested
    }

    /// Mock 데이터는 개발 빌드에서만 쓴다. 출시 빌드는 토글 값과 무관하게 항상 실제 HealthKit 데이터를 보낸다(#108).
    var activeProvider: HealthDataProvider {
        #if DEBUG
        useMockData ? mockProvider : appleProvider
        #else
        appleProvider
        #endif
    }

    /// 권한 확인 → (필요하면 전날 →) 오늘 데이터 로드 → 서버 동기화를 한 번에 수행한다.
    /// 동기화 절차는 백그라운드와 같은 `HealthSyncService`를 쓴다.
    /// 앱 실행·포그라운드 복귀 시 호출되며, 잦은 복귀로 서버를 반복 호출하지 않도록
    /// 최소 간격(AutoSyncPolicy)을 지킨다. 당겨서 새로고침은 force로 간격을 무시한다.
    /// 로그인하지 않았으면 건강 데이터를 읽지도, 서버에 보내지도 않는다.
    /// 동기화 간격과 무관하게, 동기화를 마친 뒤 Watch 조회 토큰이 만료 1일 전이면 다시 발급해 보낸다.
    /// 백그라운드 동기화가 진행 중이면 그 결과를 함께 받는다(`HealthSyncService` 단일 비행).
    func autoSync(force: Bool = false) async {
        guard let userId = userSession.userId else {
            statusMessage = userSession.reloginNotice ?? UserSession.signInPrompt
            return
        }
        if force || AutoSyncPolicy.shouldSync(lastSyncedAt: lastSyncedAt, now: Date()) {
            await sync(userId: userId)
        }
        await syncService.refreshWatchTokenIfNeeded()
    }

    private func sync(userId: String) async {
        await perform {
            try await activeProvider.requestAuthorization()
            await onHealthAuthorizationRequested()
            // 요청 중 로그아웃·계정 전환이 일어났으면 nil이고 결과를 버린다.
            guard let result = try await syncService.sync(
                provider: activeProvider,
                userId: userId,
                todayLoaded: { [weak self] in self?.snapshot = $0 }
            ) else { return }
            snapshot = result.snapshot
            let response = result.response
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
