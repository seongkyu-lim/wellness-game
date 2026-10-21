import XCTest

// MARK: - Test doubles

/// 읽은 날짜를 기록하고 고정 값을 돌려주는 건강 데이터 제공자.
final class RecordingHealthProvider: HealthDataProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var _loadedDays: [Date] = []
    private var _todayLoads = 0
    var failPreviousDay = false

    var loadedDays: [Date] { lock.withLock { _loadedDays } }
    var todayLoads: Int { lock.withLock { _todayLoads } }

    var isAvailable: Bool { true }

    func requestAuthorization() async throws {}

    func loadToday() async throws -> DailyHealthSnapshot {
        lock.withLock { _todayLoads += 1 }
        return DailyHealthSnapshot(date: Date(), steps: 100, workouts: [], sleep: nil)
    }

    func load(day: Date) async throws -> DailyHealthSnapshot {
        lock.withLock { _loadedDays.append(day) }
        if failPreviousDay {
            throw HealthDataError.invalidData
        }
        return DailyHealthSnapshot(date: day, steps: 50, workouts: [], sleep: nil)
    }
}

/// HealthKit 없이 관찰 콜백을 흉내 낸다.
final class FakeHealthUpdateObserver: HealthUpdateObserving, @unchecked Sendable {
    var authorizationRequested = true
    private(set) var startCount = 0
    private var handler: (@Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void)?

    func isAuthorizationRequested() async -> Bool {
        authorizationRequested
    }

    func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    ) {
        startCount += 1
        self.handler = handler
    }

    func fire(_ type: HealthObservedType, error: Error? = nil, completion: @escaping @Sendable () -> Void) {
        handler?(type, error, completion)
    }
}

/// 실행을 열어 줄 때까지 기다리는 동기화 작업.
@MainActor
final class GatedPerform {
    private(set) var startCount = 0
    private var continuations: [CheckedContinuation<Void, Never>] = []

    func perform() async {
        startCount += 1
        await withCheckedContinuation { continuations.append($0) }
    }

    func release() {
        let pending = continuations
        continuations = []
        pending.forEach { $0.resume() }
    }
}

/// completion 호출 횟수를 센다.
final class CompletionCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var _count = 0

    var count: Int { lock.withLock { _count } }

    func callAsFunction() {
        lock.withLock { _count += 1 }
    }
}

@MainActor
private func waitUntil(timeout: TimeInterval = 2, _ condition: () -> Bool) async {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition(), Date() < deadline {
        try? await Task.sleep(nanoseconds: 5_000_000)
    }
}

private let seoul: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
    return calendar
}()

private func seoulDate(_ text: String) -> Date {
    let formatter = DateFormatter()
    formatter.calendar = seoul
    formatter.timeZone = seoul.timeZone
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd HH:mm"
    return formatter.date(from: text)!
}

// MARK: - 전날 재전송 판단

final class SyncDayPlannerTests: XCTestCase {
    func test_noHistory_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: nil, now: seoulDate("2026-10-21 09:00"), calendar: seoul
        ))
    }

    func test_sameDay_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: seoulDate("2026-10-21 00:01"), now: seoulDate("2026-10-21 23:59"), calendar: seoul
        ))
    }

    func test_lastSuccessOnEarlierDay_resendsPreviousDay() {
        XCTAssertTrue(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: seoulDate("2026-10-20 23:59"), now: seoulDate("2026-10-21 00:01"), calendar: seoul
        ))
        XCTAssertTrue(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: seoulDate("2026-10-15 12:00"), now: seoulDate("2026-10-21 09:00"), calendar: seoul
        ))
    }

    func test_futureLastSuccess_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: seoulDate("2026-10-22 09:00"), now: seoulDate("2026-10-21 09:00"), calendar: seoul
        ))
    }

    func test_previousDay_isStartOfYesterday() {
        XCTAssertEqual(
            SyncDayPlanner.previousDay(of: seoulDate("2026-10-21 00:30"), calendar: seoul),
            seoulDate("2026-10-20 00:00")
        )
    }
}

// MARK: - 하루 구간

final class HealthDayWindowTests: XCTestCase {
    func test_today_endsNow() throws {
        let now = seoulDate("2026-10-21 15:00")
        let window = try AppleHealthKitProvider.dayWindow(for: now, now: now, calendar: seoul)

        XCTAssertEqual(window.start, seoulDate("2026-10-21 00:00"))
        XCTAssertEqual(window.end, now)
        XCTAssertEqual(window.sleepStart, seoulDate("2026-10-20 12:00"))
        XCTAssertEqual(window.snapshotDate, now)
    }

    func test_pastDay_coversWholeDay_andSleepWakingThatDay() throws {
        let now = seoulDate("2026-10-21 07:00")
        let window = try AppleHealthKitProvider.dayWindow(for: seoulDate("2026-10-20 00:00"), now: now, calendar: seoul)

        XCTAssertEqual(window.start, seoulDate("2026-10-20 00:00"))
        XCTAssertEqual(window.end, seoulDate("2026-10-21 00:00"))
        XCTAssertEqual(window.sleepStart, seoulDate("2026-10-19 12:00"))
        XCTAssertEqual(window.snapshotDate, seoulDate("2026-10-20 00:00"))
    }

    func test_futureDay_isRejected() {
        XCTAssertThrowsError(try AppleHealthKitProvider.dayWindow(
            for: seoulDate("2026-10-22 00:00"), now: seoulDate("2026-10-21 23:00"), calendar: seoul
        ))
    }
}

// MARK: - 동기화 서비스·백그라운드 실행

@MainActor
final class HealthSyncServiceTests: XCTestCase {
    private static let syncPath = "/api/health-activities/sync"
    private static let syncResponse = """
    {"userId":"password:alice","date":"2026-10-21","gainedXp":10,"levelUp":false,
     "character":{"level":1,"currentXp":10,"totalXp":10,"nextLevelXp":100,
       "stats":{"str":1,"vit":1,"intStat":1,"discipline":1,"recovery":1}},
     "activityResults":[]}
    """
    private static let watchToken = #"{"accessToken":"watch","tokenType":"Bearer","expiresIn":604800}"#

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: InMemoryTokenStore!
    private var clock: TestClock!

    override func setUp() async throws {
        suiteName = "HealthSyncServiceTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = InMemoryTokenStore()
        clock = TestClock(seoulDate("2026-10-21 09:00"))
        StubURLProtocol.setStubs([
            Self.syncPath: .init(status: 200, json: Self.syncResponse),
            "/api/auth/watch-token": .init(status: 200, json: Self.watchToken),
        ])
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeSession(signedIn: Bool) throws -> UserSession {
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        if signedIn {
            try session.signIn(provider: .password, auth: AuthTokenResponse(
                userId: "password:alice",
                displayName: "Alice",
                accessToken: "jwt",
                tokenType: "Bearer",
                expiresIn: 3600,
                userIdentifier: "alice"
            ))
        }
        return session
    }

    private func makeService(session: UserSession) -> HealthSyncService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let client = NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            tokenStore: store
        )
        let clock = clock!
        return HealthSyncService(
            networkClient: client,
            userSession: session,
            history: HealthSyncHistory(defaults: defaults),
            calendar: seoul,
            now: { clock.now }
        )
    }

    private var syncedDates: [String] {
        StubURLProtocol.requests
            .filter { $0.url?.path == Self.syncPath }
            .compactMap { StubURLProtocol.bodyData(of: $0) }
            .compactMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            .compactMap { $0["date"] as? String }
    }

    func test_sync_whenLastSuccessYesterday_sendsYesterdayThenToday() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: seoulDate("2026-10-20 22:00"))
        let provider = RecordingHealthProvider()

        let result = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertNotNil(result)
        XCTAssertEqual(provider.loadedDays, [seoulDate("2026-10-20 00:00")])
        XCTAssertEqual(provider.todayLoads, 1)
        XCTAssertEqual(syncedDates.first, "2026-10-20")
        XCTAssertEqual(syncedDates.count, 2)
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, clock.now)
    }

    func test_sync_whenAlreadySyncedToday_sendsTodayOnly() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: seoulDate("2026-10-21 08:00"))
        let provider = RecordingHealthProvider()

        _ = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertTrue(provider.loadedDays.isEmpty)
        XCTAssertEqual(syncedDates.count, 1)
    }

    func test_sync_previousDayFailure_stillSyncsToday() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: seoulDate("2026-10-19 22:00"))
        let provider = RecordingHealthProvider()
        provider.failPreviousDay = true

        let result = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertNotNil(result)
        XCTAssertEqual(syncedDates.count, 1)
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, clock.now)
    }

    func test_backgroundRun_whenSignedOut_skipsWithoutRequests() async throws {
        let service = makeService(session: try makeSession(signedIn: false))
        let provider = RecordingHealthProvider()
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: provider
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .skippedSignedOut)
        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertEqual(provider.todayLoads, 0)
    }

    func test_backgroundRun_whenLocked_skipsBeforeTouchingSession() async {
        var serviceCreated = false
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: { throw KeychainError.locked },
            syncService: {
                serviceCreated = true
                fatalError("잠금 상태에서는 세션을 만들지 않아야 한다")
            },
            provider: RecordingHealthProvider()
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .skippedLocked)
        XCTAssertFalse(serviceCreated)
    }

    func test_backgroundRun_whenProtectedDataUnavailable_skips() async {
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { false },
            checkCredentialsReadable: {},
            syncService: { fatalError("잠금 상태에서는 세션을 만들지 않아야 한다") },
            provider: RecordingHealthProvider()
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .skippedLocked)
    }

    func test_backgroundRun_whenSignedIn_syncs() async throws {
        let session = try makeSession(signedIn: true)
        let service = makeService(session: session)
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: RecordingHealthProvider()
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .synced)
        XCTAssertEqual(syncedDates.count, 1)
        XCTAssertNotNil(HealthSyncHistory(defaults: defaults).lastSuccessAt)
    }

    func test_backgroundRun_on401_signsOut() async throws {
        let session = try makeSession(signedIn: true)
        let service = makeService(session: session)
        StubURLProtocol.setStubs([
            Self.syncPath: .init(status: 401, json: #"{"message":"invalid"}"#),
            "/api/auth/watch-token": .init(status: 200, json: Self.watchToken),
        ])
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: RecordingHealthProvider()
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .failed)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
    }
}

// MARK: - 콜백 묶기·최소 간격

@MainActor
final class BackgroundSyncCoordinatorTests: XCTestCase {
    func test_concurrentCallbacks_runOneSync_andCallAllCompletions() async {
        let clock = TestClock(Date(timeIntervalSince1970: 1_800_000_000))
        let gate = GatedPerform()
        let coordinator = BackgroundSyncCoordinator(now: { clock.now }, perform: { await gate.perform() })
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        await waitUntil { gate.startCount == 1 }

        XCTAssertTrue(coordinator.isRunning)
        XCTAssertTrue(coordinator.hasQueuedRun)
        XCTAssertEqual(completions.count, 0)

        gate.release()
        await waitUntil { completions.count == 3 }

        XCTAssertEqual(gate.startCount, 1, "60초 안에 몰린 콜백은 동기화 한 번으로 처리한다")
        XCTAssertEqual(completions.count, 3)
        XCTAssertFalse(coordinator.isRunning)
        XCTAssertFalse(coordinator.hasQueuedRun)
    }

    func test_queuedRun_executesOnce_whenIntervalElapsedDuringRun() async {
        let clock = TestClock(Date(timeIntervalSince1970: 1_800_000_000))
        let gate = GatedPerform()
        let coordinator = BackgroundSyncCoordinator(now: { clock.now }, perform: { await gate.perform() })
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        await waitUntil { gate.startCount == 1 }
        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        clock.advance(61)
        gate.release()
        await waitUntil { gate.startCount == 2 }

        XCTAssertEqual(completions.count, 1)
        gate.release()
        await waitUntil { completions.count == 3 }

        XCTAssertEqual(gate.startCount, 2, "대기 중인 실행은 하나로 묶인다")
        XCTAssertEqual(completions.count, 3)
    }

    func test_minimumInterval_skipsSecondSyncWithin60Seconds() async {
        let clock = TestClock(Date(timeIntervalSince1970: 1_800_000_000))
        let gate = GatedPerform()
        let coordinator = BackgroundSyncCoordinator(now: { clock.now }, perform: { await gate.perform() })
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        await waitUntil { gate.startCount == 1 }
        gate.release()
        await waitUntil { completions.count == 1 }

        clock.advance(30)
        coordinator.handleUpdate { completions() }
        XCTAssertEqual(completions.count, 2, "실행하지 않으면 completion을 바로 부른다")
        XCTAssertFalse(coordinator.isRunning)

        clock.advance(31)
        coordinator.handleUpdate { completions() }
        await waitUntil { gate.startCount == 2 }
        XCTAssertEqual(gate.startCount, 2)
        gate.release()
        await waitUntil { completions.count == 3 }
        XCTAssertEqual(completions.count, 3)
    }

    func test_timeout_callsCompletion_evenIfSyncHangs_andOnlyOnce() async {
        let gate = GatedPerform()
        let coordinator = BackgroundSyncCoordinator(completionTimeout: 0.05, perform: { await gate.perform() })
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        await waitUntil { completions.count == 1 }
        XCTAssertEqual(completions.count, 1)

        gate.release()
        await waitUntil { !coordinator.isRunning }
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(completions.count, 1, "시간 초과 뒤 동기화가 끝나도 completion은 한 번만 부른다")
    }
}

// MARK: - 관찰 등록·completion 보장

@MainActor
final class HealthBackgroundSyncTests: XCTestCase {
    private struct ObserverError: Error {}

    func test_start_skipsQuietly_whenAuthorizationNotRequested() async {
        let observer = FakeHealthUpdateObserver()
        observer.authorizationRequested = false
        let sync = HealthBackgroundSync(observer: observer, coordinator: BackgroundSyncCoordinator(perform: {}))

        await sync.startIfAuthorized()

        XCTAssertEqual(observer.startCount, 0)
        XCTAssertFalse(sync.isObserving)
    }

    func test_start_registersOnlyOnce() async {
        let observer = FakeHealthUpdateObserver()
        let sync = HealthBackgroundSync(observer: observer, coordinator: BackgroundSyncCoordinator(perform: {}))

        await sync.startIfAuthorized()
        await sync.startIfAuthorized()

        XCTAssertEqual(observer.startCount, 1)
        XCTAssertTrue(sync.isObserving)
    }

    func test_observerError_callsCompletion_withoutSync() async {
        let observer = FakeHealthUpdateObserver()
        var performCount = 0
        let sync = HealthBackgroundSync(
            observer: observer,
            coordinator: BackgroundSyncCoordinator(perform: { performCount += 1 })
        )
        await sync.startIfAuthorized()
        let completions = CompletionCounter()

        observer.fire(.sleep, error: ObserverError()) { completions() }
        await waitUntil { completions.count == 1 }

        XCTAssertEqual(completions.count, 1)
        XCTAssertEqual(performCount, 0)
    }

    func test_update_callsCompletion_afterSync() async {
        let observer = FakeHealthUpdateObserver()
        var performCount = 0
        let sync = HealthBackgroundSync(
            observer: observer,
            coordinator: BackgroundSyncCoordinator(perform: { performCount += 1 })
        )
        await sync.startIfAuthorized()
        let completions = CompletionCounter()

        observer.fire(.workouts) { completions() }
        observer.fire(.steps) { completions() }
        observer.fire(.sleep) { completions() }
        await waitUntil { completions.count == 3 }

        XCTAssertEqual(completions.count, 3)
        XCTAssertEqual(performCount, 1)
    }

    func test_update_whenSignedOut_stillCallsCompletion() async throws {
        let observer = FakeHealthUpdateObserver()
        let suiteName = "HealthBackgroundSyncTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())
        let service = HealthSyncService(networkClient: NetworkClient(), userSession: session, history: HealthSyncHistory(defaults: defaults))
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: RecordingHealthProvider()
        )
        var outcome: BackgroundSyncOutcome?
        let sync = HealthBackgroundSync(
            observer: observer,
            coordinator: BackgroundSyncCoordinator(perform: { outcome = await runner.run() })
        )
        await sync.startIfAuthorized()
        let completions = CompletionCounter()

        observer.fire(.steps) { completions() }
        await waitUntil { completions.count == 1 }

        XCTAssertEqual(completions.count, 1)
        XCTAssertEqual(outcome, .skippedSignedOut)
    }
}
