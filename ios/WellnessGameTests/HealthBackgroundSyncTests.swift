import HealthKit
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

/// 대기 중인 MainActor 작업이 진행되도록 양보한다(벽시계를 기다리지 않는다).
@MainActor
private func settle() async {
    for _ in 0..<50 {
        await Task.yield()
    }
}

// MARK: - 전날 재전송 판단

final class SyncDayPlannerTests: XCTestCase {
    func test_noHistory_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: nil, now: kst("2026-10-21 09:00")
        ))
    }

    func test_sameServerDay_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: kst("2026-10-21 00:01"), now: kst("2026-10-21 23:59")
        ))
    }

    func test_lastSuccessOnEarlierServerDay_resendsPreviousDay() {
        XCTAssertTrue(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: kst("2026-10-20 23:59"), now: kst("2026-10-21 00:01")
        ))
        XCTAssertTrue(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: kst("2026-10-15 12:00"), now: kst("2026-10-21 09:00")
        ))
    }

    func test_decisionUsesServerTimeZone_notDeviceTimeZone() {
        // UTC로는 같은 날(10/20 14:00Z → 15:30Z)이지만 한국 시간으로는 10/20 23:00 → 10/21 00:30이다.
        XCTAssertTrue(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: kst("2026-10-20 23:00"), now: kst("2026-10-21 00:30")
        ))
        XCTAssertEqual(SyncDayPlanner.serverCalendar.timeZone.identifier, "Asia/Seoul")
    }

    func test_futureLastSuccess_syncsTodayOnly() {
        XCTAssertFalse(SyncDayPlanner.shouldResendPreviousDay(
            lastSuccessAt: kst("2026-10-22 09:00"), now: kst("2026-10-21 09:00")
        ))
    }

    func test_previousDay_isStartOfYesterday() {
        XCTAssertEqual(
            SyncDayPlanner.previousDay(of: kst("2026-10-21 00:30"), calendar: seoulCalendar),
            kst("2026-10-20 00:00")
        )
    }

    func test_previousDay_outsideServerRange_isNotSent() {
        var losAngeles = Calendar(identifier: .gregorian)
        losAngeles.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        // 한국 10/22 12:00 = LA 10/21 20:00. LA 기준 전날(10/20)은 서버 기준 오늘(10/22)에서 이틀 전이다.
        let now = kst("2026-10-22 12:00")
        let laYesterday = SyncDayPlanner.previousDay(of: now, calendar: losAngeles)!

        XCTAssertFalse(SyncDayPlanner.isAcceptedByServer(day: laYesterday, deviceCalendar: losAngeles, now: now))
        XCTAssertTrue(SyncDayPlanner.isAcceptedByServer(
            day: SyncDayPlanner.previousDay(of: now, calendar: seoulCalendar)!, deviceCalendar: seoulCalendar, now: now
        ))
    }
}

// MARK: - 하루 구간·수면

final class HealthDayWindowTests: XCTestCase {
    func test_today_endsNow() throws {
        let now = kst("2026-10-21 15:00")
        let window = try AppleHealthKitProvider.dayWindow(for: now, now: now, calendar: seoulCalendar)

        XCTAssertEqual(window.start, kst("2026-10-21 00:00"))
        XCTAssertEqual(window.end, now)
        XCTAssertEqual(window.sleepStart, kst("2026-10-20 12:00"))
        XCTAssertEqual(window.snapshotDate, now)
    }

    func test_pastDay_coversWholeDay_andSleepWakingThatDay() throws {
        let now = kst("2026-10-21 07:00")
        let window = try AppleHealthKitProvider.dayWindow(for: kst("2026-10-20 00:00"), now: now, calendar: seoulCalendar)

        XCTAssertEqual(window.start, kst("2026-10-20 00:00"))
        XCTAssertEqual(window.end, kst("2026-10-21 00:00"))
        XCTAssertEqual(window.sleepStart, kst("2026-10-19 12:00"))
        XCTAssertEqual(window.snapshotDate, kst("2026-10-20 00:00"))
    }

    func test_futureDay_isRejected() {
        XCTAssertThrowsError(try AppleHealthKitProvider.dayWindow(
            for: kst("2026-10-22 00:00"), now: kst("2026-10-21 23:00"), calendar: seoulCalendar
        ))
    }

    func test_pastDaySleep_excludesSleepWakingAfterDayEnd() throws {
        let window = try AppleHealthKitProvider.dayWindow(
            for: kst("2026-10-20 00:00"), now: kst("2026-10-21 09:00"), calendar: seoulCalendar
        )
        let core = HKCategoryValueSleepAnalysis.asleepCore.rawValue
        let samples: [AppleHealthKitProvider.SleepSample] = [
            .init(value: core, start: kst("2026-10-19 23:00"), end: kst("2026-10-20 06:00")),
            .init(value: HKCategoryValueSleepAnalysis.awake.rawValue,
                  start: kst("2026-10-20 06:00"), end: kst("2026-10-20 06:30")),
            // 자정을 넘겨 다음 날 아침에 깬 수면은 다음 날 몫이다.
            .init(value: core, start: kst("2026-10-20 23:30"), end: kst("2026-10-21 07:00")),
        ]

        let sleep = try XCTUnwrap(AppleHealthKitProvider.sleepActivity(from: samples, in: window))

        XCTAssertEqual(sleep.sleepMinutes, 420)
        XCTAssertEqual(sleep.startedAt, kst("2026-10-19 23:00"))
        XCTAssertEqual(sleep.endedAt, kst("2026-10-20 06:00"))
    }

    func test_sleep_endingExactlyAtDayEnd_isIncluded() throws {
        let window = try AppleHealthKitProvider.dayWindow(
            for: kst("2026-10-20 00:00"), now: kst("2026-10-21 09:00"), calendar: seoulCalendar
        )
        let samples: [AppleHealthKitProvider.SleepSample] = [
            .init(value: HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
                  start: kst("2026-10-20 22:00"), end: kst("2026-10-21 00:00")),
        ]

        XCTAssertEqual(AppleHealthKitProvider.sleepActivity(from: samples, in: window)?.sleepMinutes, 120)
    }
}

// MARK: - 동기화 서비스·백그라운드 실행

@MainActor
final class HealthSyncServiceTests: XCTestCase {
    private static let syncPath = "/api/health-activities/sync"
    private static let watchTokenPath = "/api/auth/watch-token"
    private static let syncResponse = """
    {"userId":"password:alice","date":"2026-10-21","gainedXp":10,"levelUp":true,
     "character":{"level":2,"currentXp":10,"totalXp":110,"nextLevelXp":200,
       "stats":{"str":1,"vit":1,"intStat":1,"discipline":1,"recovery":1}},
     "activityResults":[]}
    """
    private static let watchToken = #"{"accessToken":"watch","tokenType":"Bearer","expiresIn":604800}"#

    private var defaults: UserDefaults!
    private var store: InMemoryTokenStore!
    private var clock: TestClock!

    override func setUp() async throws {
        defaults = ScratchDefaults.make(for: self)
        store = InMemoryTokenStore()
        clock = TestClock(kst("2026-10-21 09:00"))
        stubServer(syncStatus: 200)
    }

    private func stubServer(syncStatus: Int) {
        RoutedURLProtocol.setStubs([
            Self.syncPath: syncStatus == 200
                ? .init(status: 200, json: Self.syncResponse)
                : .init(status: syncStatus, json: #"{"message":"error"}"#),
            Self.watchTokenPath: .init(status: 200, json: Self.watchToken),
        ])
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
        configuration.protocolClasses = [RoutedURLProtocol.self]
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
            calendar: seoulCalendar,
            serverCalendar: seoulCalendar,
            now: { clock.now }
        )
    }

    private func makeRunner(
        service: HealthSyncService,
        tasks: FakeBackgroundTasks = FakeBackgroundTasks(),
        provider: RecordingHealthProvider = RecordingHealthProvider(),
        isAppActive: Bool = false
    ) -> BackgroundHealthSyncRunner {
        BackgroundHealthSyncRunner(
            isAppActive: { isAppActive },
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: provider,
            backgroundTasks: tasks
        )
    }

    private var syncedDates: [String] {
        RoutedURLProtocol.requests
            .filter { $0.url?.path == Self.syncPath }
            .compactMap { RoutedURLProtocol.bodyData(of: $0) }
            .compactMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            .compactMap { $0["date"] as? String }
    }

    private var requestPaths: [String] {
        RoutedURLProtocol.requests.compactMap { $0.url?.path }
    }

    func test_sync_whenLastSuccessYesterday_sendsYesterdayThenToday() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: kst("2026-10-20 22:00"))
        let provider = RecordingHealthProvider()

        let result = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertNotNil(result)
        XCTAssertEqual(provider.loadedDays, [kst("2026-10-20 00:00")])
        XCTAssertEqual(provider.todayLoads, 1)
        XCTAssertEqual(syncedDates.first, "2026-10-20")
        XCTAssertEqual(syncedDates.count, 2)
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, clock.now)
    }

    func test_sync_whenAlreadySyncedToday_sendsTodayOnly() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: kst("2026-10-21 08:00"))
        let provider = RecordingHealthProvider()

        _ = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertTrue(provider.loadedDays.isEmpty)
        XCTAssertEqual(syncedDates.count, 1)
    }

    func test_sync_previousDayFailure_stillSyncsToday() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        HealthSyncHistory(defaults: defaults).recordSuccess(at: kst("2026-10-19 22:00"))
        let provider = RecordingHealthProvider()
        provider.failPreviousDay = true

        let result = try await service.sync(provider: provider, userId: "password:alice")

        XCTAssertNotNil(result)
        XCTAssertEqual(syncedDates.count, 1)
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, clock.now)
    }

    func test_sync_401DuringPreviousDay_stopsAndThrows() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        let lastSuccess = kst("2026-10-20 22:00")
        HealthSyncHistory(defaults: defaults).recordSuccess(at: lastSuccess)
        stubServer(syncStatus: 401)
        let provider = RecordingHealthProvider()

        do {
            _ = try await service.sync(provider: provider, userId: "password:alice")
            XCTFail("401은 호출한 쪽으로 던져야 한다")
        } catch NetworkError.unauthorized {
        }

        XCTAssertEqual(syncedDates, ["2026-10-20"], "전날 401이면 오늘 요청을 보내지 않는다")
        XCTAssertEqual(provider.todayLoads, 0)
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, lastSuccess)
    }

    func test_concurrentForegroundAndBackgroundSync_shareOneRequestAndResponse() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        let provider = RecordingHealthProvider()

        async let foreground = service.sync(provider: provider, userId: "password:alice")
        async let background = service.sync(provider: provider, userId: "password:alice")
        let (first, second) = try await (foreground, background)

        XCTAssertEqual(provider.todayLoads, 1)
        XCTAssertEqual(syncedDates.count, 1)
        XCTAssertEqual(first?.response.levelUp, true)
        XCTAssertEqual(second?.response.levelUp, true, "합류한 호출자도 레벨업 결과를 받는다")

        // 끝난 뒤의 새 호출은 다시 보낸다.
        _ = try await service.sync(provider: provider, userId: "password:alice")
        XCTAssertEqual(syncedDates.count, 2)
    }

    func test_backgroundRun_whenSignedOut_skipsWithoutRequests_andEndsTask() async throws {
        let service = makeService(session: try makeSession(signedIn: false))
        let provider = RecordingHealthProvider()
        let tasks = FakeBackgroundTasks()

        let outcome = await makeRunner(service: service, tasks: tasks, provider: provider).run()

        XCTAssertEqual(outcome, .skippedSignedOut)
        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
        XCTAssertEqual(provider.todayLoads, 0)
        XCTAssertEqual(tasks.begun, 1)
        XCTAssertEqual(tasks.ended, [1])
    }

    func test_backgroundRun_whenAppActive_leavesItToForeground() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        let tasks = FakeBackgroundTasks()

        let outcome = await makeRunner(service: service, tasks: tasks, isAppActive: true).run()

        XCTAssertEqual(outcome, .skippedForeground)
        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
        XCTAssertEqual(tasks.begun, 0)
    }

    func test_backgroundRun_whenLocked_skipsBeforeTouchingSession_andEndsTask() async {
        var serviceCreated = false
        let tasks = FakeBackgroundTasks()
        let runner = BackgroundHealthSyncRunner(
            isAppActive: { false },
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: { throw KeychainError.locked },
            syncService: {
                serviceCreated = true
                fatalError("잠금 상태에서는 세션을 만들지 않아야 한다")
            },
            provider: RecordingHealthProvider(),
            backgroundTasks: tasks
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .skippedLocked)
        XCTAssertFalse(serviceCreated)
        XCTAssertEqual(tasks.ended, [1])
    }

    func test_backgroundRun_whenProtectedDataUnavailable_skips() async {
        let runner = BackgroundHealthSyncRunner(
            isAppActive: { false },
            isProtectedDataAvailable: { false },
            checkCredentialsReadable: {},
            syncService: { fatalError("잠금 상태에서는 세션을 만들지 않아야 한다") },
            provider: RecordingHealthProvider(),
            backgroundTasks: FakeBackgroundTasks()
        )

        let outcome = await runner.run()

        XCTAssertEqual(outcome, .skippedLocked)
    }

    func test_backgroundRun_whenSignedIn_syncsThenRefreshesWatchToken() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        let tasks = FakeBackgroundTasks()

        let outcome = await makeRunner(service: service, tasks: tasks).run()

        XCTAssertEqual(outcome, .synced)
        XCTAssertEqual(requestPaths, [Self.syncPath, Self.watchTokenPath], "Watch 토큰은 동기화 뒤에 갱신한다")
        XCTAssertNotNil(HealthSyncHistory(defaults: defaults).lastSuccessAt)
        XCTAssertEqual(tasks.ended, [1])
    }

    func test_backgroundRelaunch_doesNotReissueWatchToken() async throws {
        let service = makeService(session: try makeSession(signedIn: true))
        _ = await makeRunner(service: service).run()
        XCTAssertEqual(requestPaths.filter { $0 == Self.watchTokenPath }.count, 1)

        // 앱을 다시 실행: 같은 저장소로 새 세션을 복원한다.
        let relaunched = makeService(session: try makeSession(signedIn: false))
        XCTAssertTrue(relaunched.userSession.isSignedIn)
        _ = await makeRunner(service: relaunched).run()

        XCTAssertEqual(requestPaths.filter { $0 == Self.watchTokenPath }.count, 1, "만료가 멀면 다시 발급하지 않는다")
    }

    func test_backgroundRun_on401_signsOut_andEndsTask() async throws {
        let session = try makeSession(signedIn: true)
        let service = makeService(session: session)
        stubServer(syncStatus: 401)
        let tasks = FakeBackgroundTasks()

        let outcome = await makeRunner(service: service, tasks: tasks).run()

        XCTAssertEqual(outcome, .failed)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertEqual(tasks.ended, [1])
    }
}

// MARK: - 잠금 중 세션 복원

@MainActor
final class LockedRestoreTests: XCTestCase {
    func test_restoreWhileLocked_keepsCredentials_andRestoresAfterUnlock() throws {
        let suiteName = "LockedRestoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set("password", forKey: "auth.provider")
        defaults.set("password:alice", forKey: "auth.userId")
        let accessStore = LockableTokenStore(token: "jwt")
        let refreshStore = LockableTokenStore(token: "refresh")
        accessStore.isLocked = true
        refreshStore.isLocked = true
        let center = NotificationCenter()
        let unlocked = Notification.Name("test.protectedDataDidBecomeAvailable")

        let session = UserSession(
            defaults: defaults,
            credentials: SessionCredentialStore(accessStore: accessStore, refreshStore: refreshStore, defaults: defaults),
            watchPublisher: NoopWatchPublisher(),
            notificationCenter: center,
            protectedDataNotification: unlocked
        )

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice)
        XCTAssertEqual(accessStore.storedToken, "jwt", "잠금 중에는 저장된 토큰을 지우지 않는다")
        XCTAssertEqual(refreshStore.storedToken, "refresh")
        XCTAssertEqual(defaults.string(forKey: "auth.userId"), "password:alice")

        accessStore.isLocked = false
        refreshStore.isLocked = false
        center.post(name: unlocked, object: nil)

        XCTAssertTrue(session.isSignedIn)
        XCTAssertEqual(session.userId, "password:alice")
    }
}

// MARK: - 콜백 묶기·최소 간격·시간 초과

@MainActor
final class BackgroundSyncCoordinatorTests: XCTestCase {
    private static let timeout: TimeInterval = 100

    private var clock: TestClock!
    private var gate: GatedPerform!
    private var sleeper: ManualSleeper!
    private var refreshInFlight = false

    override func setUp() async throws {
        clock = TestClock(Date(timeIntervalSince1970: 1_800_000_000))
        gate = GatedPerform()
        sleeper = ManualSleeper()
        refreshInFlight = false
    }

    override func tearDown() async throws {
        // 남은 sleep·실행을 모두 풀어 continuation이 새지 않게 한다.
        refreshInFlight = false
        for _ in 0..<3 {
            sleeper.wake { _ in true }
            gate.release()
            await settle()
        }
    }

    private func makeCoordinator() -> BackgroundSyncCoordinator {
        let clock = clock!
        let gate = gate!
        let sleeper = sleeper!
        return BackgroundSyncCoordinator(
            completionTimeout: Self.timeout,
            now: { clock.now },
            sleep: { await sleeper.sleep($0) },
            isRefreshInFlight: { [unowned self] in self.refreshInFlight },
            perform: { await gate.perform() }
        )
    }

    /// 실행 하나를 끝내 최소 간격 기준 시각을 만든다.
    private func finishFirstRun(_ coordinator: BackgroundSyncCoordinator, _ completions: CompletionCounter) async {
        coordinator.handleUpdate { completions() }
        await settle()
        gate.release()
        await settle()
    }

    func test_concurrentCallbacks_runOneSync_andCallAllCompletions() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        await settle()

        XCTAssertEqual(gate.startCount, 1)
        XCTAssertTrue(coordinator.hasQueuedRun)
        XCTAssertEqual(completions.count, 0)

        gate.release()
        await settle()

        XCTAssertEqual(gate.startCount, 1, "60초 안에 몰린 콜백은 동기화 한 번으로 처리한다")
        XCTAssertEqual(completions.count, 3)
        XCTAssertFalse(coordinator.isRunning)
        XCTAssertFalse(coordinator.hasQueuedRun)
        XCTAssertFalse(coordinator.hasTrailingRun)
    }

    func test_queuedRun_executesOnce_whenIntervalElapsedDuringRun() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        await settle()
        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        clock.advance(61)
        gate.release()
        await settle()

        XCTAssertEqual(gate.startCount, 2)
        XCTAssertEqual(completions.count, 1)
        gate.release()
        await settle()

        XCTAssertEqual(gate.startCount, 2, "대기 중인 실행은 하나로 묶인다")
        XCTAssertEqual(completions.count, 3)
    }

    func test_updateNearEndOfInterval_runsTrailingSync_andHoldsCompletion() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()
        await finishFirstRun(coordinator, completions)

        clock.advance(55)
        coordinator.handleUpdate { completions() }
        coordinator.handleUpdate { completions() }
        await settle()

        XCTAssertTrue(coordinator.hasTrailingRun)
        XCTAssertEqual(completions.count, 1, "뒤따르는 실행이 끝날 때까지 completion을 기다린다")
        XCTAssertTrue(sleeper.pendingDelays.contains(5))

        clock.advance(5)
        sleeper.wake { $0 == 5 }
        await settle()
        XCTAssertEqual(gate.startCount, 2)

        gate.release()
        await settle()
        XCTAssertEqual(gate.startCount, 2, "간격 안의 콜백은 뒤따르는 실행 하나로 묶인다")
        XCTAssertEqual(completions.count, 3)
    }

    func test_updateEarlyInInterval_beyondTimeBudget_completesWithoutSync() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()
        await finishFirstRun(coordinator, completions)

        clock.advance(30)
        coordinator.handleUpdate { completions() }

        XCTAssertEqual(completions.count, 2, "기다릴 수 없으면 completion을 바로 부른다")
        XCTAssertFalse(coordinator.hasTrailingRun)
        XCTAssertEqual(gate.startCount, 1)

        clock.advance(31)
        coordinator.handleUpdate { completions() }
        await settle()
        XCTAssertEqual(gate.startCount, 2)
        gate.release()
        await settle()
        XCTAssertEqual(completions.count, 3)
    }

    func test_timeout_callsCompletion_evenIfSyncHangs_andOnlyOnce() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()

        coordinator.handleUpdate { completions() }
        await settle()
        sleeper.wake { $0 == Self.timeout }
        await settle()
        XCTAssertEqual(completions.count, 1)

        gate.release()
        await settle()
        XCTAssertEqual(completions.count, 1, "시간 초과 뒤 동기화가 끝나도 completion은 한 번만 부른다")
    }

    func test_timeout_waitsWhileTokenRefreshInFlight() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()
        refreshInFlight = true

        coordinator.handleUpdate { completions() }
        await settle()
        sleeper.wake { $0 == Self.timeout }
        await settle()

        XCTAssertEqual(completions.count, 0, "리프레시 응답을 저장하기 전에는 completion을 부르지 않는다")
        XCTAssertEqual(sleeper.pendingDelays, [BackgroundSyncCoordinator.refreshHoldStep])

        refreshInFlight = false
        sleeper.wake { $0 == BackgroundSyncCoordinator.refreshHoldStep }
        await settle()
        XCTAssertEqual(completions.count, 1)
        gate.release()
        await settle()
    }

    func test_timeoutHold_isBounded() async {
        let coordinator = makeCoordinator()
        let completions = CompletionCounter()
        refreshInFlight = true

        coordinator.handleUpdate { completions() }
        await settle()
        sleeper.wake { $0 == Self.timeout }
        await settle()
        let steps = Int(BackgroundSyncCoordinator.defaultRefreshHoldLimit / BackgroundSyncCoordinator.refreshHoldStep)
        for _ in 0..<steps {
            sleeper.wake { $0 == BackgroundSyncCoordinator.refreshHoldStep }
            await settle()
        }

        XCTAssertEqual(completions.count, 1, "리프레시가 끝나지 않아도 한도가 지나면 completion을 부른다")
        gate.release()
        await settle()
    }
}

// MARK: - 관찰 등록·completion 보장

@MainActor
final class HealthBackgroundSyncTests: XCTestCase {
    private struct ObserverError: Error {}
    private let unlocked = Notification.Name("test.protectedDataDidBecomeAvailable")
    private var center: NotificationCenter!

    override func setUp() async throws {
        center = NotificationCenter()
    }

    private func makeSync(
        observer: FakeHealthUpdateObserver,
        perform: @escaping @MainActor () async -> Void = {}
    ) -> HealthBackgroundSync {
        HealthBackgroundSync(
            observer: observer,
            coordinator: BackgroundSyncCoordinator(perform: perform),
            notificationCenter: center,
            protectedDataNotification: unlocked
        )
    }

    func test_start_registersObserversSynchronously_evenBeforeAuthorization() async {
        let observer = FakeHealthUpdateObserver()
        observer.answers = [.notRequested]
        var performCount = 0
        let sync = makeSync(observer: observer) { performCount += 1 }

        sync.start()
        XCTAssertEqual(observer.startCount, 1, "관찰 쿼리는 실행 시점에 바로 등록한다")
        await settle()

        XCTAssertFalse(sync.isDeliveryEnabled)
        XCTAssertEqual(observer.enableCount, 0)

        let completions = CompletionCounter()
        observer.fire(.steps) { completions() }
        await settle()
        XCTAssertEqual(completions.count, 1, "전달을 켜기 전 콜백도 completion을 부른다")
        XCTAssertEqual(performCount, 0)
    }

    func test_start_enablesDeliveryOnce() async {
        let observer = FakeHealthUpdateObserver()
        let sync = makeSync(observer: observer)

        sync.start()
        sync.start()
        await sync.enableDeliveryIfAuthorized()
        await settle()

        XCTAssertEqual(observer.startCount, 1)
        XCTAssertEqual(observer.enableCount, 1)
        XCTAssertTrue(sync.isDeliveryEnabled)
    }

    func test_concurrentEnableCall_isNotLost() async {
        let observer = FakeHealthUpdateObserver()
        observer.answers = [.notRequested, .requested]
        observer.holdAuthorization = true
        let sync = makeSync(observer: observer)

        sync.start()
        await settle()
        XCTAssertEqual(observer.authorizationChecks, 1)

        // 첫 확인이 진행 중일 때 포그라운드에서 권한을 요청하고 다시 부른다.
        let second = Task { await sync.enableDeliveryIfAuthorized() }
        await settle()
        observer.releaseAuthorization()
        await second.value

        XCTAssertEqual(observer.authorizationChecks, 2, "진행 중 확인이 끝난 뒤 한 번 더 확인한다")
        XCTAssertTrue(sync.isDeliveryEnabled)
    }

    func test_unknownAuthorization_retriesWhenProtectedDataBecomesAvailable() async {
        let observer = FakeHealthUpdateObserver()
        observer.answers = [.unknown, .requested]
        let sync = makeSync(observer: observer)

        sync.start()
        await settle()
        XCTAssertFalse(sync.isDeliveryEnabled)

        center.post(name: unlocked, object: nil)
        await settle()

        XCTAssertTrue(sync.isDeliveryEnabled)
        XCTAssertEqual(observer.enableCount, 1)
    }

    func test_callbackDuringAuthorizationCheck_waitsThenSyncs() async {
        let observer = FakeHealthUpdateObserver()
        observer.holdAuthorization = true
        var performCount = 0
        let sync = makeSync(observer: observer) { performCount += 1 }
        let completions = CompletionCounter()

        sync.start()
        observer.fire(.workouts) { completions() }
        await settle()
        XCTAssertEqual(completions.count, 0)

        observer.releaseAuthorization()
        await settle()

        XCTAssertEqual(performCount, 1, "백그라운드 재실행의 밀린 전달을 버리지 않는다")
        XCTAssertEqual(completions.count, 1)
    }

    func test_observerError_callsCompletion_withoutSync() async {
        let observer = FakeHealthUpdateObserver()
        var performCount = 0
        let sync = makeSync(observer: observer) { performCount += 1 }
        sync.start()
        await settle()
        let completions = CompletionCounter()

        observer.fire(.sleep, error: ObserverError()) { completions() }
        await settle()

        XCTAssertEqual(completions.count, 1)
        XCTAssertEqual(performCount, 0)
    }

    func test_update_callsCompletion_afterSync() async {
        let observer = FakeHealthUpdateObserver()
        var performCount = 0
        let sync = makeSync(observer: observer) { performCount += 1 }
        sync.start()
        await settle()
        let completions = CompletionCounter()

        observer.fire(.workouts) { completions() }
        observer.fire(.steps) { completions() }
        observer.fire(.sleep) { completions() }
        await settle()

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
            isAppActive: { false },
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: {},
            syncService: { service },
            provider: RecordingHealthProvider(),
            backgroundTasks: FakeBackgroundTasks()
        )
        var outcome: BackgroundSyncOutcome?
        let sync = makeSync(observer: observer) { outcome = await runner.run() }
        sync.start()
        await settle()
        let completions = CompletionCounter()

        observer.fire(.steps) { completions() }
        await settle()

        XCTAssertEqual(completions.count, 1)
        XCTAssertEqual(outcome, .skippedSignedOut)
    }
}
