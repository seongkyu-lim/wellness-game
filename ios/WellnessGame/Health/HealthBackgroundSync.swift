import Foundation
import HealthKit
import OSLog

private let logger = Logger(subsystem: "io.github.seongkyulim.wellnessgame", category: "BackgroundSync")

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
    /// 관찰 쿼리를 등록한다. 권한이 없는 타입에 등록해도 해가 없으므로 실행 직후 동기적으로 부른다.
    /// `handler`는 임의 스레드에서 불리며, 넘겨받은 completion은 반드시 한 번 호출해야 한다.
    /// 호출하지 않으면 iOS가 전달을 재시도하다가 반복되면 백그라운드 전달을 멈춘다.
    func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    )

    /// 사용자에게 HealthKit 권한 요청을 이미 했는지(다시 물을 필요가 없는지).
    /// 읽기 권한은 개인정보 보호를 위해 허용 여부를 알 수 없으므로 "요청했는지"만 확인한다.
    /// 상태를 알 수 없으면(첫 잠금 해제 전·DB 접근 불가) 던진다.
    func isAuthorizationRequested() async throws -> Bool

    /// 관찰 중인 타입의 백그라운드 전달을 켠다.
    func enableBackgroundDelivery()
}

/// 실제 HealthKit 구현.
final class HealthKitUpdateObserver: HealthUpdateObserving {
    private let manager: HealthKitManager
    private var queries: [HKObserverQuery] = []

    init(manager: HealthKitManager) {
        self.manager = manager
    }

    func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    ) {
        for type in HealthObservedType.allCases {
            guard let sampleType = type.sampleType else { continue }
            let query = HKObserverQuery(sampleType: sampleType, predicate: nil) { _, completionHandler, error in
                let completion = UncheckedCompletion(call: completionHandler)
                handler(type, error) { completion.call() }
            }
            queries.append(query)
            manager.store.execute(query)
        }
    }

    func isAuthorizationRequested() async throws -> Bool {
        guard manager.isAvailable else { return false }
        let status = try await manager.store.statusForAuthorizationRequest(toShare: [], read: manager.readTypes)
        switch status {
        case .unnecessary:
            return true
        case .shouldRequest:
            return false
        default:
            throw HealthDataError.invalidData
        }
    }

    func enableBackgroundDelivery() {
        for type in HealthObservedType.allCases {
            guard let sampleType = type.sampleType else { continue }
            manager.store.enableBackgroundDelivery(for: sampleType, frequency: type.frequency) { success, error in
                if !success {
                    logger.error(
                        "백그라운드 전달을 켜지 못했어요(\(String(describing: type), privacy: .public)): \(error?.localizedDescription ?? "-", privacy: .public)"
                    )
                }
            }
        }
    }
}

/// HealthKit completion handler를 다른 스레드로 넘기기 위한 포장. HealthKit은 어느 스레드에서 불러도 된다.
private struct UncheckedCompletion: @unchecked Sendable {
    let call: () -> Void
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
/// - 마지막 실행 시작 후 `minimumInterval`(기본 60초)이 지나지 않았으면 바로 실행하지 않는다.
///   걸음·운동·수면 관찰이 거의 동시에 불려도 동기화는 한 번만 나간다.
/// - 간격 안에 온 콜백은 간격이 끝나는 시점에 뒤따르는 실행 하나로 묶는다. 다만 백그라운드 실행 시간이 짧으므로
///   기다릴 시간이 `maxTrailingDelay`보다 길면 실행하지 않고 completion을 부른다. 이렇게 놓친 변경은
///   다음 전달·포그라운드 동기화·다음 날 전날 재전송에서 다시 보낸다(서버는 중복을 거른다).
/// - completion은 해당 콜백을 처리한 실행이 끝나면, 실행하지 않으면 즉시, 늦어도 `completionTimeout` 뒤에 부른다.
///   단 토큰 리프레시가 나가 있으면 회전된 토큰이 저장될 때까지 `refreshHoldLimit`만큼 더 기다린다.
///   completion을 부르면 iOS가 앱을 정지할 수 있어, 리프레시 응답을 저장하지 못하면 새 리프레시 토큰을 잃는다.
@MainActor
final class BackgroundSyncCoordinator {
    nonisolated static let defaultMinimumInterval: TimeInterval = 60
    nonisolated static let defaultCompletionTimeout: TimeInterval = 20
    nonisolated static let defaultMaxTrailingDelay: TimeInterval = 10
    nonisolated static let defaultRefreshHoldLimit: TimeInterval = 15
    nonisolated static let refreshHoldStep: TimeInterval = 0.5

    private let minimumInterval: TimeInterval
    private let completionTimeout: TimeInterval
    private let maxTrailingDelay: TimeInterval
    private let refreshHoldLimit: TimeInterval
    private let now: () -> Date
    private let sleep: (TimeInterval) async -> Void
    private let isRefreshInFlight: () -> Bool
    private let perform: @MainActor () async -> Void

    private(set) var isRunning = false
    private var lastRunStartedAt: Date?
    private var currentCompletions: [OnceCompletion] = []
    /// nil이면 실행 뒤 대기 중인 실행이 없다.
    private var queuedCompletions: [OnceCompletion]?
    /// nil이면 예약된 뒤따르는 실행이 없다.
    private var trailingCompletions: [OnceCompletion]?

    init(
        minimumInterval: TimeInterval = BackgroundSyncCoordinator.defaultMinimumInterval,
        completionTimeout: TimeInterval = BackgroundSyncCoordinator.defaultCompletionTimeout,
        maxTrailingDelay: TimeInterval = BackgroundSyncCoordinator.defaultMaxTrailingDelay,
        refreshHoldLimit: TimeInterval = BackgroundSyncCoordinator.defaultRefreshHoldLimit,
        now: @escaping () -> Date = Date.init,
        sleep: @escaping (TimeInterval) async -> Void = { seconds in
            try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
        },
        isRefreshInFlight: @escaping () -> Bool = { false },
        perform: @escaping @MainActor () async -> Void
    ) {
        self.minimumInterval = minimumInterval
        self.completionTimeout = completionTimeout
        self.maxTrailingDelay = maxTrailingDelay
        self.refreshHoldLimit = refreshHoldLimit
        self.now = now
        self.sleep = sleep
        self.isRefreshInFlight = isRefreshInFlight
        self.perform = perform
    }

    var hasQueuedRun: Bool {
        queuedCompletions != nil
    }

    var hasTrailingRun: Bool {
        trailingCompletions != nil
    }

    func handleUpdate(completion: @escaping () -> Void) {
        let once = OnceCompletion(completion)
        scheduleTimeout(for: once)
        if isRunning {
            queuedCompletions = (queuedCompletions ?? []) + [once]
        } else if let remaining = remainingInterval {
            scheduleTrailing([once], after: remaining)
        } else {
            startRun(completions: [once])
        }
    }

    /// 최소 간격까지 남은 시간. 지났으면 nil.
    private var remainingInterval: TimeInterval? {
        guard let lastRunStartedAt else { return nil }
        let date = now()
        // 기기 시간이 뒤로 바뀌어 미래 시각이 남아 있으면 막히지 않도록 지난 것으로 본다.
        if lastRunStartedAt > date { return nil }
        let remaining = minimumInterval - date.timeIntervalSince(lastRunStartedAt)
        return remaining > 0 ? remaining : nil
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
        if let remaining = remainingInterval {
            scheduleTrailing(queued, after: remaining)
        } else {
            startRun(completions: queued)
        }
    }

    private func scheduleTrailing(_ completions: [OnceCompletion], after delay: TimeInterval) {
        if let trailing = trailingCompletions {
            trailingCompletions = trailing + completions
            return
        }
        guard delay <= maxTrailingDelay else {
            logger.info("최소 간격 안의 변경이라 이번에는 동기화하지 않습니다(남은 \(delay, privacy: .public)초).")
            completions.forEach { $0.call() }
            return
        }
        trailingCompletions = completions
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.sleep(delay)
            self.fireTrailing()
        }
    }

    private func fireTrailing() {
        guard let trailing = trailingCompletions else { return }
        trailingCompletions = nil
        if isRunning {
            queuedCompletions = (queuedCompletions ?? []) + trailing
        } else {
            startRun(completions: trailing)
        }
    }

    private func scheduleTimeout(for once: OnceCompletion) {
        Task { @MainActor [weak self] in
            guard let self else {
                once.call()
                return
            }
            await self.sleep(self.completionTimeout)
            var held: TimeInterval = 0
            while !once.isCalled, self.isRefreshInFlight(), held < self.refreshHoldLimit {
                await self.sleep(Self.refreshHoldStep)
                held += Self.refreshHoldStep
            }
            if !once.isCalled {
                logger.info("동기화가 제한 시간 안에 끝나지 않아 HealthKit completion을 먼저 호출합니다.")
            }
            once.call()
        }
    }
}

/// 백그라운드 실행 시간 요청(`UIApplication.beginBackgroundTask`). 테스트에서는 가짜 구현으로 대체한다.
@MainActor
protocol BackgroundTaskProviding {
    /// 시작한 작업을 끝내는 함수를 돌려준다. 시간이 다 되면 `expiration` 뒤에 iOS가 정지하므로 그 안에서도 끝낸다.
    func begin(name: String, expiration: @escaping @MainActor () -> Void) -> Int
    func end(_ identifier: Int)
}

/// 백그라운드 동기화 한 번의 결과.
enum BackgroundSyncOutcome: Equatable {
    case synced
    /// 앱이 화면에 떠 있어 포그라운드 자동 동기화에 맡겼다.
    case skippedForeground
    /// 기기가 잠겨 HealthKit·Keychain을 읽을 수 없다.
    case skippedLocked
    case skippedSignedOut
    case failed
}

/// 백그라운드 동기화 한 번을 실행한다. 화면 상태는 건드리지 않는다.
///
/// - 실행 전체를 백그라운드 작업(`beginBackgroundTask`)으로 감싸 리프레시 중에 앱이 정지되지 않게 한다.
///   어떤 경로로 끝나도(성공·실패·건너뜀·시간 만료) 작업을 한 번만 끝낸다.
/// - 세션·동기화 서비스는 클로저로 늦게 만든다. 첫 잠금 해제 전 백그라운드 실행에서 `UserSession`을 만들면
///   Keychain·UserDefaults를 읽지 못하므로, 잠금 확인을 통과한 뒤에만 만든다.
/// - Watch 조회 토큰 갱신은 동기화 뒤에 한다(시간이 모자라면 동기화가 먼저다).
@MainActor
struct BackgroundHealthSyncRunner {
    let isAppActive: () -> Bool
    let isProtectedDataAvailable: () -> Bool
    let checkCredentialsReadable: () throws -> Void
    let syncService: () -> HealthSyncService
    let provider: HealthDataProvider
    let backgroundTasks: BackgroundTaskProviding

    func run() async -> BackgroundSyncOutcome {
        // 앱이 화면에 있으면 포그라운드 자동 동기화가 같은 일을 한다(관찰 쿼리는 등록 직후에도 한 번 불린다).
        guard !isAppActive() else { return .skippedForeground }

        var identifier: Int?
        let end = {
            guard let current = identifier else { return }
            identifier = nil
            backgroundTasks.end(current)
        }
        identifier = backgroundTasks.begin(name: "HealthBackgroundSync") { end() }
        defer { end() }

        return await syncOnce()
    }

    private func syncOnce() async -> BackgroundSyncOutcome {
        // 잠금 상태에서는 HealthKit 데이터도 암호화돼 읽을 수 없다. iOS가 잠금 해제 후 다시 알려 준다.
        guard isProtectedDataAvailable() else { return .skippedLocked }
        do {
            try checkCredentialsReadable()
        } catch {
            logger.info("Keychain을 읽을 수 없어 백그라운드 동기화를 건너뜁니다: \(error.localizedDescription, privacy: .public)")
            return .skippedLocked
        }

        let service = syncService()
        let session = service.userSession
        guard let userId = session.userId else { return .skippedSignedOut }

        let outcome: BackgroundSyncOutcome
        do {
            outcome = try await service.sync(provider: provider, userId: userId) != nil ? .synced : .skippedSignedOut
        } catch NetworkError.unauthorized {
            // 리프레시까지 거절됐으면 세션이 끝난 것이다. 요청 도중 계정이 바뀌었으면 새 세션은 두고 버린다.
            if session.userId == userId {
                session.handleUnauthorized()
            }
            return .failed
        } catch {
            logger.info("백그라운드 동기화 실패: \(error.localizedDescription, privacy: .public)")
            outcome = .failed
        }

        // 포그라운드와 같이 Watch 조회 토큰도 만료가 가까우면 갱신해 보낸다.
        await service.refreshWatchTokenIfNeeded()
        return outcome
    }
}

/// HealthKit 백그라운드 전달로 앱을 열지 않아도 건강 데이터를 서버에 동기화한다.
///
/// - 앱 실행 시(`AppDelegate.application(_:didFinishLaunchingWithOptions:)`) `start()`를 부른다.
///   관찰 쿼리는 그 자리에서 동기적으로 등록하고(백그라운드 재실행에서 밀린 전달을 받으려면 바로 등록해야 한다),
///   백그라운드 전달 켜기만 권한 요청 여부를 확인한 뒤에 한다.
/// - 권한 요청을 아직 하지 않았으면 조용히 건너뛴다. 포그라운드에서 권한을 요청한 뒤 다시 부르면 그때 켠다.
/// - 권한 상태를 알 수 없으면(첫 잠금 해제 전 등) 잠금이 풀릴 때(`protectedDataDidBecomeAvailable`) 다시 확인한다.
/// - 관찰 콜백마다 completion을 반드시 부른다(오류·시간 초과·로그아웃·권한 없음 포함).
@MainActor
final class HealthBackgroundSync {
    private let observer: HealthUpdateObserving
    private let coordinator: BackgroundSyncCoordinator
    private let notificationCenter: NotificationCenter
    private let protectedDataNotification: Notification.Name
    private(set) var isObserving = false
    private(set) var isDeliveryEnabled = false
    private var authorizationCheck: Task<Void, Never>?
    private var protectedDataObserver: NSObjectProtocol?

    init(
        observer: HealthUpdateObserving,
        coordinator: BackgroundSyncCoordinator,
        notificationCenter: NotificationCenter = .default,
        protectedDataNotification: Notification.Name
    ) {
        self.observer = observer
        self.coordinator = coordinator
        self.notificationCenter = notificationCenter
        self.protectedDataNotification = protectedDataNotification
    }

    /// 관찰 쿼리를 바로 등록하고(한 번만), 권한 확인 후 백그라운드 전달을 켠다.
    func start() {
        if !isObserving {
            isObserving = true
            observer.startObserving { [weak self] type, error, completion in
                Task { @MainActor in
                    guard let self else {
                        completion()
                        return
                    }
                    await self.handleUpdate(type: type, error: error, completion: completion)
                }
            }
        }
        // 콜백 처리가 권한 확인을 기다릴 수 있도록 확인 작업은 동기적으로 걸어 둔다.
        if authorizationCheck == nil, !isDeliveryEnabled {
            beginAuthorizationCheck()
        } else {
            Task { await enableDeliveryIfAuthorized() }
        }
    }

    /// 권한 요청을 이미 했으면 백그라운드 전달을 켠다. 여러 번 불러도 한 번만 켠다.
    /// 확인이 진행 중일 때 불리면 그 확인이 끝난 뒤 한 번 더 확인한다(그사이 권한을 요청했을 수 있다).
    func enableDeliveryIfAuthorized() async {
        if let running = authorizationCheck {
            await running.value
        }
        guard !isDeliveryEnabled else { return }
        // 기다리는 동안 다른 호출이 새 확인을 시작했으면 그 결과를 따른다.
        if let running = authorizationCheck {
            await running.value
            return
        }
        await beginAuthorizationCheck().value
    }

    @discardableResult
    private func beginAuthorizationCheck() -> Task<Void, Never> {
        let check = Task { @MainActor in
            await self.checkAuthorizationAndEnable()
            self.authorizationCheck = nil
        }
        authorizationCheck = check
        return check
    }

    private func checkAuthorizationAndEnable() async {
        do {
            guard try await observer.isAuthorizationRequested() else { return }
            observer.enableBackgroundDelivery()
            isDeliveryEnabled = true
            stopWaitingForProtectedData()
        } catch {
            logger.info("HealthKit 권한 상태를 알 수 없어 잠금 해제 후 다시 확인합니다: \(error.localizedDescription, privacy: .public)")
            waitForProtectedData()
        }
    }

    private func waitForProtectedData() {
        guard protectedDataObserver == nil else { return }
        protectedDataObserver = notificationCenter.addObserver(
            forName: protectedDataNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.stopWaitingForProtectedData()
                Task { await self.enableDeliveryIfAuthorized() }
            }
        }
    }

    private func stopWaitingForProtectedData() {
        guard let protectedDataObserver else { return }
        notificationCenter.removeObserver(protectedDataObserver)
        self.protectedDataObserver = nil
    }

    func handleUpdate(type: HealthObservedType, error: Error?, completion: @escaping () -> Void) async {
        if let error {
            logger.error("HealthKit 관찰 오류(\(String(describing: type), privacy: .public)): \(error.localizedDescription, privacy: .public)")
            completion()
            return
        }
        // 실행 직후 권한 확인이 끝나기 전에 온 콜백(백그라운드 재실행의 밀린 전달)은 확인이 끝날 때까지 기다린다.
        if let running = authorizationCheck {
            await running.value
        }
        // 권한을 요청하기 전(전달을 켜기 전)의 콜백은 동기화하지 않는다. 빈 데이터를 보내지 않기 위해서다.
        guard isDeliveryEnabled else {
            completion()
            return
        }
        coordinator.handleUpdate(completion: completion)
    }
}
