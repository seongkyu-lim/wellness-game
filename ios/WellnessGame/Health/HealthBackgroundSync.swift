import Foundation
import HealthKit
import OSLog

/// 백그라운드 전달을 받는 HealthKit 데이터 종류.
enum HealthObservedType: CaseIterable, Sendable {
    case steps
    case workouts
    case sleep

    /// 요청하는 백그라운드 전달 빈도.
    ///
    /// 운동·수면은 기록이 끝나면 바로 반영되도록 `.immediate`를 요청한다.
    /// 걸음 수는 샘플이 계속 쌓이는 타입이라 `.hourly`를 요청한다. iOS는 걸음 수 같은 일부 수량 타입의
    /// 백그라운드 전달을 최대 1시간 단위로 제한하므로 `.immediate`를 요청해도 실제로는 시간 단위로 묶여 온다.
    /// 또한 iOS는 배터리·사용 패턴에 따라 전달을 늦추거나 묶을 수 있어 정확한 시점은 보장되지 않는다.
    var frequency: HKUpdateFrequency {
        switch self {
        case .steps: .hourly
        case .workouts, .sleep: .immediate
        }
    }

    var sampleType: HKSampleType? {
        switch self {
        case .steps: HKQuantityType.quantityType(forIdentifier: .stepCount)
        case .workouts: HKObjectType.workoutType()
        case .sleep: HKObjectType.categoryType(forIdentifier: .sleepAnalysis)
        }
    }
}

/// HealthKit 관찰(HKObserverQuery + 백그라운드 전달) 중 동기화에 필요한 부분. 테스트에서는 가짜 구현으로 대체한다.
protocol HealthUpdateObserving: AnyObject {
    /// 사용자에게 HealthKit 권한 요청을 이미 했는지(다시 물을 필요가 없는지).
    /// 읽기 권한은 개인정보 보호를 위해 허용 여부를 알 수 없으므로 "요청했는지"만 확인한다.
    func isAuthorizationRequested() async -> Bool

    /// 관찰을 시작하고 백그라운드 전달을 켠다.
    /// `handler`는 임의 스레드에서 불리며, 넘겨받은 completion은 반드시 한 번 호출해야 한다.
    /// 호출하지 않으면 iOS가 전달을 재시도하다가 반복되면 백그라운드 전달을 멈춘다.
    func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    )
}

/// 실제 HealthKit 구현.
final class HealthKitUpdateObserver: HealthUpdateObserving {
    private let manager: HealthKitManager
    private var queries: [HKObserverQuery] = []

    init(manager: HealthKitManager) {
        self.manager = manager
    }

    func isAuthorizationRequested() async -> Bool {
        guard manager.isAvailable else { return false }
        do {
            let status = try await manager.store.statusForAuthorizationRequest(toShare: [], read: manager.readTypes)
            return status == .unnecessary
        } catch {
            return false
        }
    }

    func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    ) {
        for type in HealthObservedType.allCases {
            guard let sampleType = type.sampleType else { continue }
            let query = HKObserverQuery(sampleType: sampleType, predicate: nil) { _, completionHandler, error in
                let completion = UncheckedCompletion(completionHandler)
                handler(type, error) { completion.call() }
            }
            queries.append(query)
            manager.store.execute(query)
            manager.store.enableBackgroundDelivery(for: sampleType, frequency: type.frequency) { success, error in
                if !success {
                    Self.logger.error(
                        "백그라운드 전달을 켜지 못했어요(\(String(describing: type), privacy: .public)): \(error?.localizedDescription ?? "-", privacy: .public)"
                    )
                }
            }
        }
    }

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "BackgroundSync")
}

/// HealthKit completion handler를 다른 스레드로 넘기기 위한 포장. HealthKit은 어느 스레드에서 불러도 된다.
private struct UncheckedCompletion: @unchecked Sendable {
    let call: () -> Void

    init(_ call: @escaping () -> Void) {
        self.call = call
    }
}

/// 여러 번 불려도 한 번만 실행되는 completion.
@MainActor
final class OnceCompletion {
    private var completion: (() -> Void)?

    init(_ completion: @escaping () -> Void) {
        self.completion = completion
    }

    var isCalled: Bool {
        completion == nil
    }

    func call() {
        let pending = completion
        completion = nil
        pending?()
    }
}

/// 관찰 콜백을 동기화 실행으로 묶는다.
///
/// - 한 번에 하나만 실행한다. 실행 중에 온 콜백은 "다음 실행 한 번"으로 모아 둔다(최대 하나만 대기).
/// - 마지막 실행 시작 후 `minimumInterval`(기본 60초)이 지나지 않았으면 실행하지 않는다.
///   걸음·운동·수면 관찰이 거의 동시에 불려도 동기화는 한 번만 나간다. 대기 중이던 실행도 같은 기준을 따르므로
///   짧은 실행 중에 몰려온 콜백은 첫 실행으로 처리된 것으로 본다. 놓친 변경은 다음 콜백·포그라운드 동기화·
///   다음 날 전날 재전송에서 다시 보낸다(서버는 중복을 거른다).
/// - completion은 해당 콜백을 처리한 실행이 끝나면, 실행하지 않으면 즉시, 늦어도 `completionTimeout` 뒤에 부른다.
@MainActor
final class BackgroundSyncCoordinator {
    nonisolated static let defaultMinimumInterval: TimeInterval = 60
    /// 백그라운드 실행 시간이 짧으므로 동기화가 끝나지 않아도 이 시간이 지나면 completion을 부른다.
    nonisolated static let defaultCompletionTimeout: TimeInterval = 20

    private let minimumInterval: TimeInterval
    private let completionTimeout: TimeInterval
    private let now: () -> Date
    private let perform: @MainActor () async -> Void

    private(set) var isRunning = false
    private var lastRunStartedAt: Date?
    private var currentCompletions: [OnceCompletion] = []
    /// nil이면 대기 중인 실행이 없다.
    private var queuedCompletions: [OnceCompletion]?

    init(
        minimumInterval: TimeInterval = BackgroundSyncCoordinator.defaultMinimumInterval,
        completionTimeout: TimeInterval = BackgroundSyncCoordinator.defaultCompletionTimeout,
        now: @escaping () -> Date = Date.init,
        perform: @escaping @MainActor () async -> Void
    ) {
        self.minimumInterval = minimumInterval
        self.completionTimeout = completionTimeout
        self.now = now
        self.perform = perform
    }

    var hasQueuedRun: Bool {
        queuedCompletions != nil
    }

    func handleUpdate(completion: @escaping () -> Void) {
        let once = OnceCompletion(completion)
        scheduleTimeout(for: once)
        if isRunning {
            queuedCompletions = (queuedCompletions ?? []) + [once]
            return
        }
        guard isIntervalElapsed else {
            once.call()
            return
        }
        startRun(completions: [once])
    }

    private var isIntervalElapsed: Bool {
        guard let lastRunStartedAt else { return true }
        let date = now()
        // 기기 시간이 뒤로 바뀌어 미래 시각이 남아 있으면 막히지 않도록 지난 것으로 본다.
        if lastRunStartedAt > date { return true }
        return date.timeIntervalSince(lastRunStartedAt) >= minimumInterval
    }

    private func startRun(completions: [OnceCompletion]) {
        isRunning = true
        lastRunStartedAt = now()
        currentCompletions = completions
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.perform()
            self.finishRun()
        }
    }

    private func finishRun() {
        isRunning = false
        let finished = currentCompletions
        currentCompletions = []
        finished.forEach { $0.call() }

        guard let queued = queuedCompletions else { return }
        queuedCompletions = nil
        if isIntervalElapsed {
            startRun(completions: queued)
        } else {
            queued.forEach { $0.call() }
        }
    }

    private func scheduleTimeout(for once: OnceCompletion) {
        let timeout = completionTimeout
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(max(timeout, 0) * 1_000_000_000))
            if !once.isCalled {
                Self.logger.info("동기화가 제한 시간 안에 끝나지 않아 HealthKit completion을 먼저 호출합니다.")
            }
            once.call()
        }
    }

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "BackgroundSync")
}

/// 백그라운드 동기화 한 번의 결과.
enum BackgroundSyncOutcome: Equatable {
    case synced
    /// 기기가 잠겨 HealthKit·Keychain을 읽을 수 없다.
    case skippedLocked
    case skippedSignedOut
    case failed
}

/// 백그라운드 동기화 한 번을 실행한다. 화면 상태는 건드리지 않는다.
///
/// 세션·동기화 서비스는 클로저로 늦게 만든다. 첫 잠금 해제 전 백그라운드 실행에서 `UserSession`을 만들면
/// Keychain·UserDefaults를 읽지 못해 로그아웃 상태로 복원되므로, 잠금 확인을 통과한 뒤에만 만든다.
@MainActor
struct BackgroundHealthSyncRunner {
    let isProtectedDataAvailable: () -> Bool
    let checkCredentialsReadable: () throws -> Void
    let syncService: () -> HealthSyncService
    let provider: HealthDataProvider

    func run() async -> BackgroundSyncOutcome {
        // 잠금 상태에서는 HealthKit 데이터도 암호화돼 읽을 수 없다. iOS가 잠금 해제 후 다시 알려 준다.
        guard isProtectedDataAvailable() else { return .skippedLocked }
        do {
            try checkCredentialsReadable()
        } catch {
            Self.logger.info("Keychain을 읽을 수 없어 백그라운드 동기화를 건너뜁니다: \(error.localizedDescription, privacy: .public)")
            return .skippedLocked
        }

        let service = syncService()
        let session = service.userSession
        guard let userId = session.userId else { return .skippedSignedOut }

        // 포그라운드와 같이 Watch 조회 토큰도 만료가 가까우면 갱신해 보낸다.
        await service.refreshWatchTokenIfNeeded()
        guard session.userId == userId else { return .skippedSignedOut }

        do {
            guard try await service.sync(provider: provider, userId: userId) != nil else {
                return .skippedSignedOut
            }
            return .synced
        } catch NetworkError.unauthorized {
            // 리프레시까지 거절됐으면 세션이 끝난 것이다. 요청 도중 계정이 바뀌었으면 새 세션은 두고 버린다.
            if session.userId == userId {
                session.handleUnauthorized()
            }
            return .failed
        } catch {
            Self.logger.info("백그라운드 동기화 실패: \(error.localizedDescription, privacy: .public)")
            return .failed
        }
    }

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "BackgroundSync")
}

/// HealthKit 백그라운드 전달로 앱을 열지 않아도 건강 데이터를 서버에 동기화한다.
///
/// - 앱 실행 시(`AppDelegate.application(_:didFinishLaunchingWithOptions:)`) `startIfAuthorized()`를 부른다.
///   iOS가 백그라운드 전달로 앱을 다시 실행할 때도 이 경로를 지나므로 관찰이 다시 등록된다.
/// - 권한 요청을 아직 하지 않았으면 조용히 건너뛴다. 포그라운드에서 권한을 요청한 뒤 다시 부르면 그때 시작한다.
/// - 관찰 콜백마다 completion을 반드시 부른다(오류·시간 초과·로그아웃 포함).
@MainActor
final class HealthBackgroundSync {
    private let observer: HealthUpdateObserving
    private let coordinator: BackgroundSyncCoordinator
    private(set) var isObserving = false
    private var isStarting = false

    init(observer: HealthUpdateObserving, coordinator: BackgroundSyncCoordinator) {
        self.observer = observer
        self.coordinator = coordinator
    }

    /// 여러 번 불러도 관찰은 한 번만 등록한다.
    func startIfAuthorized() async {
        guard !isObserving, !isStarting else { return }
        isStarting = true
        defer { isStarting = false }
        guard await observer.isAuthorizationRequested() else { return }
        isObserving = true
        observer.startObserving { [weak self] type, error, completion in
            Task { @MainActor in
                guard let self else {
                    completion()
                    return
                }
                self.handleUpdate(type: type, error: error, completion: completion)
            }
        }
    }

    func handleUpdate(type: HealthObservedType, error: Error?, completion: @escaping () -> Void) {
        if let error {
            Self.logger.error("HealthKit 관찰 오류(\(String(describing: type), privacy: .public)): \(error.localizedDescription, privacy: .public)")
            completion()
            return
        }
        coordinator.handleUpdate(completion: completion)
    }

    private static let logger = Logger(subsystem: "com.example.WellnessGame", category: "BackgroundSync")
}
