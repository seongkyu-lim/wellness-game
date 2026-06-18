import Foundation
import Combine

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published private(set) var snapshot: DailyHealthSnapshot?
    @Published private(set) var syncResponse: HealthActivitySyncResponse?
    @Published private(set) var isLoading = false
    @Published private(set) var statusMessage = "HealthKit 권한을 요청해 주세요."
    @Published var useMockData = false

    private let appleProvider: HealthDataProvider
    private let mockProvider: HealthDataProvider
    private let networkClient: NetworkClient
    private let userId: String

    init(
        appleProvider: HealthDataProvider = AppleHealthKitProvider(),
        mockProvider: HealthDataProvider = MockHealthDataProvider(),
        networkClient: NetworkClient = NetworkClient(),
        userId: String = "test-user"
    ) {
        self.appleProvider = appleProvider
        self.mockProvider = mockProvider
        self.networkClient = networkClient
        self.userId = userId
    }

    var activeProvider: HealthDataProvider {
        useMockData ? mockProvider : appleProvider
    }

    func requestAuthorization() async {
        await perform {
            try await activeProvider.requestAuthorization()
            statusMessage = useMockData ? "테스트 데이터 모드입니다." : "HealthKit 권한 요청을 완료했습니다."
        }
    }

    func loadToday() async {
        await perform {
            snapshot = try await activeProvider.loadToday()
            syncResponse = nil
            statusMessage = "오늘 건강 데이터를 불러왔습니다."
        }
    }

    func sync() async {
        guard let snapshot else {
            statusMessage = "먼저 오늘 데이터를 불러와 주세요."
            return
        }
        await perform {
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyy-MM-dd"
            let request = HealthActivitySyncRequest(
                userId: userId,
                date: formatter.string(from: snapshot.date),
                activities: snapshot.activities
            )
            syncResponse = try await networkClient.sync(request)
            statusMessage = syncResponse?.levelUp == true
                ? "레벨업! 성장 결과를 확인하세요."
                : "서버 동기화를 완료했습니다."
        }
    }

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
