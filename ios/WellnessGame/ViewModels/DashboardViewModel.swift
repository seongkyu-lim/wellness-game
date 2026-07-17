import Foundation
import Combine

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published private(set) var snapshot: DailyHealthSnapshot?
    @Published private(set) var syncResponse: HealthActivitySyncResponse?
    @Published private(set) var isLoading = false
    @Published private(set) var statusMessage = "건강 데이터를 자동으로 동기화합니다."
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
    func autoSync(force: Bool = false) async {
        guard force || AutoSyncPolicy.shouldSync(lastSyncedAt: lastSyncedAt, now: Date()) else {
            return
        }
        await perform {
            try await activeProvider.requestAuthorization()
            snapshot = try await activeProvider.loadToday()

            guard let snapshot else {
                statusMessage = "오늘 건강 데이터가 아직 없습니다."
                return
            }
            let request = HealthActivitySyncRequest(
                userId: userSession.userId,
                date: Self.dateFormatter.string(from: snapshot.date),
                activities: snapshot.activities
            )
            syncResponse = try await networkClient.sync(request)
            lastSyncedAt = Date()
            statusMessage = syncResponse?.levelUp == true
                ? "레벨업! 성장 결과를 확인하세요."
                : "자동 동기화 완료"
        }
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
        do {
            try await operation()
        } catch {
            statusMessage = error.localizedDescription
        }
    }
}
