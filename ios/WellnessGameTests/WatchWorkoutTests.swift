import HealthKit
import XCTest

// MARK: - 종목 매핑

final class WorkoutKindMappingTests: XCTestCase {
    func test_serverTypes_matchServerEnumAndIPhoneDTO() {
        XCTAssertEqual(
            WorkoutKind.allCases.map(\.serverType),
            ["RUNNING", "WALKING", "CYCLING", "SWIMMING", "STRENGTH_TRAINING", "OTHER"]
        )
        // iPhone 동기화 DTO의 WorkoutType과 값이 하나도 어긋나지 않아야 한다.
        for kind in WorkoutKind.allCases {
            XCTAssertNotNil(WorkoutType(rawValue: kind.serverType), "\(kind) 에 대응하는 WorkoutType 없음")
        }
    }

    func test_watchActivityType_roundTripsToSameServerType() {
        for kind in WorkoutKind.allCases {
            XCTAssertEqual(WorkoutKind(activityType: kind.activityType), kind)
        }
    }

    func test_healthKitTypes_mapLikeIPhoneSync() {
        let expected: [(HKWorkoutActivityType, WorkoutKind)] = [
            (.running, .running),
            (.walking, .walking),
            (.cycling, .cycling),
            (.swimming, .swimming),
            (.traditionalStrengthTraining, .strengthTraining),
            (.functionalStrengthTraining, .strengthTraining),
            (.yoga, .other),
            (.hiking, .other),
            (.other, .other),
        ]
        for (activityType, kind) in expected {
            XCTAssertEqual(WorkoutKind(activityType: activityType), kind, "\(activityType.rawValue)")
        }
    }

    func test_swimmingConfiguration_usesPoolAndDefaultLapLength() throws {
        let configuration = WorkoutKind.swimming.makeConfiguration()
        XCTAssertEqual(configuration.activityType, .swimming)
        XCTAssertEqual(configuration.swimmingLocationType, .pool)
        XCTAssertEqual(configuration.locationType, .indoor)
        let lapLength = try XCTUnwrap(configuration.lapLength)
        XCTAssertEqual(lapLength.doubleValue(for: .meter()), 25, accuracy: 0.001)
    }

    func test_nonSwimmingConfiguration_hasNoLapLength() {
        let running = WorkoutKind.running.makeConfiguration()
        XCTAssertEqual(running.activityType, .running)
        XCTAssertEqual(running.locationType, .outdoor)
        XCTAssertNil(running.lapLength)
        XCTAssertEqual(WorkoutKind.strengthTraining.makeConfiguration().locationType, .indoor)
    }

    func test_distanceTracking_onlyForDistanceSports() {
        XCTAssertEqual(WorkoutKind.running.distanceTypeIdentifier, .distanceWalkingRunning)
        XCTAssertEqual(WorkoutKind.walking.distanceTypeIdentifier, .distanceWalkingRunning)
        XCTAssertEqual(WorkoutKind.cycling.distanceTypeIdentifier, .distanceCycling)
        XCTAssertEqual(WorkoutKind.swimming.distanceTypeIdentifier, .distanceSwimming)
        XCTAssertNil(WorkoutKind.strengthTraining.distanceTypeIdentifier)
        XCTAssertNil(WorkoutKind.other.distanceTypeIdentifier)
    }
}

// MARK: - 서버 검증 규칙 호환

final class WorkoutSyncRulesTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_790_000_000)

    func test_durationMinutes_roundsAndKeepsAtLeastOneMinute() {
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: 0), 1)
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: 29), 1)
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: 89), 1)
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: 90), 2)
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: 30 * 60 + 29), 30)
        XCTAssertEqual(WorkoutSyncRules.durationMinutes(activeDuration: -5), 1)
    }

    /// 서버는 `분 ≤ 구간(분, 내림) + 1`만 받는다. 반올림·최소 1분이 이 범위를 넘지 않아야 한다.
    func test_roundedMinutes_neverExceedServerTolerance() {
        for seconds in stride(from: 1.0, through: 3 * 3600, by: 7) {
            let end = start.addingTimeInterval(seconds)
            let minutes = WorkoutSyncRules.durationMinutes(activeDuration: seconds)
            XCTAssertLessThanOrEqual(minutes, WorkoutSyncRules.maxAcceptedMinutes(start: start, end: end), "\(seconds)s")
        }
    }

    func test_pausedWorkout_activeTimeShorterThanInterval_staysValid() {
        // 40분 구간 중 10분 일시정지 → 활동 30분.
        let end = start.addingTimeInterval(40 * 60)
        let minutes = WorkoutSyncRules.durationMinutes(activeDuration: 30 * 60)
        XCTAssertEqual(minutes, 30)
        XCTAssertLessThanOrEqual(minutes, WorkoutSyncRules.maxAcceptedMinutes(start: start, end: end))
    }

    func test_calories_withinCap_areKept() {
        let end = start.addingTimeInterval(30 * 60)
        XCTAssertEqual(WorkoutSyncRules.clampedCalories(250.46, start: start, end: end), 250.4)
        XCTAssertNil(WorkoutSyncRules.clampedCalories(nil, start: start, end: end))
        XCTAssertEqual(WorkoutSyncRules.clampedCalories(-3, start: start, end: end), 0)
    }

    /// 서버는 `칼로리 × 60 > 20 × 구간(초)`면 운동 전체를 건너뛴다. 잘라 보낸 값은 항상 통과해야 한다.
    func test_calories_aboveCap_areClampedBelowServerLimit() throws {
        for seconds in [1.0, 59, 60, 61, 601.9, 1800, 7200] {
            let end = start.addingTimeInterval(seconds)
            let clamped = try XCTUnwrap(WorkoutSyncRules.clampedCalories(10_000, start: start, end: end))
            // 초 단위 ISO-8601 인코딩으로 구간이 1초 줄어든 최악의 경우에도 통과.
            let serverSeconds = max(0, seconds.rounded(.down) - 1)
            XCTAssertLessThanOrEqual(clamped * 60, WorkoutSyncRules.maxCaloriesPerMinute * serverSeconds, "\(seconds)s")
        }
        let thirtyMinutes = start.addingTimeInterval(30 * 60)
        XCTAssertEqual(try XCTUnwrap(WorkoutSyncRules.clampedCalories(900, start: start, end: thirtyMinutes)), 599.6, accuracy: 0.001)
    }
}

// MARK: - 요약 계산

final class WorkoutSummaryTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_790_000_000)

    func test_summary_roundsDisplayValues_andComputesSyncValues() {
        let summary = WorkoutSummary(
            kind: .running,
            startDate: start,
            endDate: start.addingTimeInterval(35 * 60),
            activeDuration: 30 * 60 + 40,
            metrics: WorkoutMetrics(heartRate: 150, averageHeartRate: 141.6, activeCalories: 312.5, distanceMeters: 5_012)
        )
        XCTAssertEqual(summary.roundedCalories, 313)
        XCTAssertEqual(summary.roundedAverageHeartRate, 142)
        XCTAssertEqual(summary.distanceMeters, 5_012)
        XCTAssertEqual(summary.syncedMinutes, 31)
        XCTAssertEqual(summary.syncedCalories, 312.5)
    }

    func test_summary_clampsActiveDurationToInterval() {
        let summary = WorkoutSummary(
            kind: .walking,
            startDate: start,
            endDate: start.addingTimeInterval(600),
            activeDuration: 900,
            metrics: .empty
        )
        XCTAssertEqual(summary.activeDuration, 600)
        XCTAssertNil(summary.roundedCalories)
        XCTAssertNil(summary.roundedAverageHeartRate)
    }

    func test_summary_dropsDistanceForNonDistanceSports_andInvalidHeartRate() {
        let summary = WorkoutSummary(
            kind: .strengthTraining,
            startDate: start,
            endDate: start.addingTimeInterval(1200),
            activeDuration: 1200,
            metrics: WorkoutMetrics(heartRate: nil, averageHeartRate: 0, activeCalories: 150, distanceMeters: 30)
        )
        XCTAssertNil(summary.distanceMeters)
        XCTAssertNil(summary.averageHeartRate)
        XCTAssertEqual(summary.syncedMinutes, 20)
    }

    func test_summary_caloriesAboveServerCap_areClampedForSync() throws {
        let summary = WorkoutSummary(
            kind: .cycling,
            startDate: start,
            endDate: start.addingTimeInterval(60),
            activeDuration: 60,
            metrics: WorkoutMetrics(activeCalories: 50)
        )
        XCTAssertEqual(summary.roundedCalories, 50)
        XCTAssertLessThanOrEqual(try XCTUnwrap(summary.syncedCalories), 20)
    }
}

// MARK: - 상태 머신

final class WorkoutStateMachineTests: XCTestCase {
    private let summary = WorkoutSummary(
        kind: .running,
        startDate: Date(timeIntervalSince1970: 0),
        endDate: Date(timeIntervalSince1970: 600),
        activeDuration: 600,
        metrics: .empty
    )

    private func run(_ events: [WorkoutEvent], from phase: WorkoutPhase = .idle) -> WorkoutPhase {
        events.reduce(phase) { WorkoutStateMachine.transition(from: $0, on: $1) ?? $0 }
    }

    func test_fullLifecycle_startPauseResumeEnd() {
        var phase = WorkoutPhase.idle
        phase = WorkoutStateMachine.transition(from: phase, on: .startRequested(.running))!
        XCTAssertEqual(phase, .starting(.running))
        phase = WorkoutStateMachine.transition(from: phase, on: .sessionStarted)!
        XCTAssertEqual(phase, .running(.running))
        phase = WorkoutStateMachine.transition(from: phase, on: .sessionPaused)!
        XCTAssertEqual(phase, .paused(.running))
        phase = WorkoutStateMachine.transition(from: phase, on: .sessionResumed)!
        XCTAssertEqual(phase, .running(.running))
        phase = WorkoutStateMachine.transition(from: phase, on: .endRequested)!
        XCTAssertEqual(phase, .ending(.running))
        phase = WorkoutStateMachine.transition(from: phase, on: .sessionFinished(summary))!
        XCTAssertEqual(phase, .ended(summary))
        phase = WorkoutStateMachine.transition(from: phase, on: .dismissed)!
        XCTAssertEqual(phase, .idle)
    }

    func test_endWhilePaused_isAllowed() {
        XCTAssertEqual(run([.startRequested(.cycling), .sessionStarted, .sessionPaused, .endRequested]), .ending(.cycling))
    }

    func test_invalidEvents_areIgnored() {
        XCTAssertNil(WorkoutStateMachine.transition(from: .idle, on: .endRequested))
        XCTAssertNil(WorkoutStateMachine.transition(from: .idle, on: .sessionStarted))
        XCTAssertNil(WorkoutStateMachine.transition(from: .starting(.walking), on: .endRequested))
        XCTAssertNil(WorkoutStateMachine.transition(from: .running(.walking), on: .startRequested(.running)))
        XCTAssertNil(WorkoutStateMachine.transition(from: .ending(.walking), on: .endRequested))
        XCTAssertNil(WorkoutStateMachine.transition(from: .running(.walking), on: .dismissed))
        XCTAssertFalse(WorkoutStateMachine.accepts(.pauseRequested, in: .paused(.walking)))
        XCTAssertFalse(WorkoutStateMachine.accepts(.resumeRequested, in: .running(.walking)))
        XCTAssertTrue(WorkoutStateMachine.accepts(.pauseRequested, in: .running(.walking)))
        XCTAssertTrue(WorkoutStateMachine.accepts(.resumeRequested, in: .paused(.walking)))
    }

    func test_failures_leadToFailedThenIdle() {
        XCTAssertEqual(run([.startRequested(.swimming), .sessionFailed(.startFailed)]), .failed(.startFailed))
        XCTAssertEqual(run([.startRequested(.swimming), .sessionStarted, .endRequested, .sessionFailed(.saveFailed)]), .failed(.saveFailed))
        XCTAssertEqual(run([.dismissed], from: .failed(.saveFailed)), .idle)
    }

    func test_systemEndedSession_showsSummary() {
        XCTAssertEqual(run([.sessionFinished(summary)], from: .running(.running)), .ended(summary))
    }

    func test_recovery_onlyFromIdle() {
        XCTAssertEqual(run([.sessionRecovered(.walking), .sessionPaused]), .paused(.walking))
        XCTAssertNil(WorkoutStateMachine.transition(from: .running(.running), on: .sessionRecovered(.walking)))
    }

    func test_isActive() {
        XCTAssertFalse(WorkoutPhase.idle.isActive)
        XCTAssertTrue(WorkoutPhase.starting(.other).isActive)
        XCTAssertTrue(WorkoutPhase.paused(.other).isActive)
        XCTAssertFalse(WorkoutPhase.ended(summary).isActive)
        XCTAssertEqual(WorkoutPhase.ended(summary).kind, .running)
    }
}

// MARK: - 뷰모델

@MainActor
final class FakeWorkoutController: WorkoutSessionControlling {
    enum Call: Equatable {
        case authorize, start(WorkoutKind), pause, resume, end
    }

    var onEvent: ((WorkoutEvent) -> Void)?
    var onMetrics: ((WorkoutMetrics) -> Void)?
    var isHealthDataAvailable = true
    var authorizationError: Error?
    var startError: Error?
    /// true면 start/pause/resume을 받는 즉시 세션 이벤트를 보낸다.
    var autoRespond = true
    private(set) var calls: [Call] = []

    func requestAuthorization() async throws {
        calls.append(.authorize)
        if let authorizationError { throw authorizationError }
    }

    func start(_ kind: WorkoutKind) async throws {
        calls.append(.start(kind))
        if let startError { throw startError }
        if autoRespond { onEvent?(.sessionStarted) }
    }

    func pause() {
        calls.append(.pause)
        if autoRespond { onEvent?(.sessionPaused) }
    }

    func resume() {
        calls.append(.resume)
        if autoRespond { onEvent?(.sessionResumed) }
    }

    func end() {
        calls.append(.end)
    }

    func elapsedTime(at date: Date) -> TimeInterval { 42 }

    func recoverActiveSession() async {}
}

private struct StubError: Error {}

@MainActor
final class WorkoutViewModelTests: XCTestCase {
    private var controller: FakeWorkoutController!
    private var model: WorkoutViewModel!

    override func setUp() async throws {
        controller = FakeWorkoutController()
        model = WorkoutViewModel(controller: controller)
    }

    func test_start_requestsAuthorizationThenStartsSession() async {
        await model.start(.running)
        XCTAssertEqual(controller.calls, [.authorize, .start(.running)])
        XCTAssertEqual(model.phase, .running(.running))
    }

    func test_pauseResumeEnd_followSessionEvents() async {
        await model.start(.cycling)
        model.togglePause()
        XCTAssertEqual(model.phase, .paused(.cycling))
        XCTAssertTrue(model.isPaused)
        model.togglePause()
        XCTAssertEqual(model.phase, .running(.cycling))
        model.end()
        XCTAssertEqual(model.phase, .ending(.cycling))
        XCTAssertEqual(controller.calls, [.authorize, .start(.cycling), .pause, .resume, .end])

        let summary = WorkoutSummary(kind: .cycling, startDate: Date(), endDate: Date().addingTimeInterval(60), activeDuration: 60, metrics: .empty)
        controller.onEvent?(.sessionFinished(summary))
        XCTAssertEqual(model.phase, .ended(summary))
        model.dismiss()
        XCTAssertEqual(model.phase, .idle)
    }

    func test_pauseIsNotShownUntilSessionConfirms() async {
        controller.autoRespond = false
        await model.start(.walking)
        XCTAssertEqual(model.phase, .starting(.walking))
        controller.onEvent?(.sessionStarted)
        model.pause()
        XCTAssertEqual(model.phase, .running(.walking))
        controller.onEvent?(.sessionPaused)
        XCTAssertEqual(model.phase, .paused(.walking))
    }

    func test_commandsInWrongState_areNotSent() async {
        model.pause()
        model.resume()
        model.end()
        XCTAssertTrue(controller.calls.isEmpty)
        await model.start(.other)
        await model.start(.running)
        XCTAssertEqual(controller.calls, [.authorize, .start(.other)])
    }

    func test_healthDataUnavailable_fails() async {
        controller.isHealthDataAvailable = false
        await model.start(.running)
        XCTAssertEqual(model.phase, .failed(.healthDataUnavailable))
        XCTAssertTrue(controller.calls.isEmpty)
    }

    func test_authorizationError_failsWithoutStarting() async {
        controller.authorizationError = StubError()
        await model.start(.swimming)
        XCTAssertEqual(model.phase, .failed(.authorizationFailed))
        XCTAssertEqual(controller.calls, [.authorize])
    }

    func test_startError_fails_andCanRetryAfterDismiss() async {
        controller.startError = StubError()
        await model.start(.swimming)
        XCTAssertEqual(model.phase, .failed(.startFailed))
        model.dismiss()
        controller.startError = nil
        await model.start(.swimming)
        XCTAssertEqual(model.phase, .running(.swimming))
    }

    func test_metrics_areForwarded_andResetOnNewWorkout() async {
        await model.start(.running)
        let metrics = WorkoutMetrics(heartRate: 130, averageHeartRate: 128, activeCalories: 40, distanceMeters: 800)
        controller.onMetrics?(metrics)
        XCTAssertEqual(model.metrics, metrics)
        XCTAssertEqual(model.elapsedTime(at: Date()), 42)
        model.end()
        controller.onEvent?(.sessionFailed(.saveFailed))
        model.dismiss()
        XCTAssertEqual(model.metrics, .empty)
    }
}

// MARK: - 세션 컨트롤러(가짜 HealthKit 세션)

final class FakeSessionDriver: WorkoutSessionDriving {
    let kind: WorkoutKind
    var state: HKWorkoutSessionState
    var startDate: Date?
    let signals: AsyncStream<WorkoutSessionSignal>
    private let continuation: AsyncStream<WorkoutSessionSignal>.Continuation

    var beginError: Error?
    var endCollectionError: Error?
    var finishError: Error?
    var metrics = WorkoutMetrics.empty
    var elapsed: TimeInterval = 0
    private(set) var calls: [String] = []

    init(kind: WorkoutKind, state: HKWorkoutSessionState = .notStarted, startDate: Date? = nil) {
        self.kind = kind
        self.state = state
        self.startDate = startDate
        (signals, continuation) = AsyncStream.makeStream(of: WorkoutSessionSignal.self)
    }

    func emit(_ signal: WorkoutSessionSignal) {
        continuation.yield(signal)
    }

    private func change(to newState: HKWorkoutSessionState) {
        let old = state
        state = newState
        emit(.stateChanged(to: newState, from: old, date: Date()))
    }

    func startActivity(at date: Date) {
        calls.append("startActivity")
        startDate = date
        change(to: .running)
    }

    func pause() { calls.append("pause"); change(to: .paused) }
    func resume() { calls.append("resume"); change(to: .running) }
    func end() { calls.append("end"); change(to: .ended) }

    func beginCollection(at date: Date) async throws {
        calls.append("beginCollection")
        if let beginError { throw beginError }
    }

    func endCollection(at date: Date) async throws {
        calls.append("endCollection")
        if let endCollectionError { throw endCollectionError }
    }

    func finishWorkout() async throws -> FinishedWorkout? {
        calls.append("finishWorkout")
        if let finishError { throw finishError }
        let start = startDate ?? Date()
        return FinishedWorkout(startDate: start, endDate: start.addingTimeInterval(elapsed), duration: elapsed)
    }

    func discardWorkout() { calls.append("discardWorkout") }
    func currentMetrics() -> WorkoutMetrics { metrics }
    func elapsedTime(at date: Date) -> TimeInterval { elapsed }
}

final class FakeHealthStore: WorkoutHealthStoring {
    var isHealthDataAvailable = true
    var isWorkoutSharingDenied = false
    var pendingSessions: [FakeSessionDriver] = []
    var recoverable: FakeSessionDriver?
    private(set) var madeSessions = 0
    private(set) var authorizationRequests = 0

    func requestAuthorization() async throws {
        authorizationRequests += 1
    }

    func makeSession(for kind: WorkoutKind) throws -> WorkoutSessionDriving {
        madeSessions += 1
        return pendingSessions.isEmpty ? FakeSessionDriver(kind: kind) : pendingSessions.removeFirst()
    }

    func recoverActiveSession() async -> WorkoutSessionDriving? {
        recoverable
    }
}

@MainActor
final class WorkoutSessionControllerTests: XCTestCase {
    private var store: FakeHealthStore!
    private var controller: WorkoutSessionController!
    private var model: WorkoutViewModel!
    private var waterLocks = 0

    override func setUp() async throws {
        store = FakeHealthStore()
        waterLocks = 0
        controller = WorkoutSessionController(store: store, enableWaterLock: { [unowned self] in self.waterLocks += 1 })
        model = WorkoutViewModel(controller: controller)
    }

    /// 신호 소비 루프가 돌 때까지 메인 액터를 양보하며 기다린다.
    private func waitFor(_ phase: WorkoutPhase, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<200 where model.phase != phase {
            await Task.yield()
        }
        XCTAssertEqual(model.phase, phase, file: file, line: line)
    }

    private func startRunning(_ driver: FakeSessionDriver) async {
        store.pendingSessions = [driver]
        await model.start(driver.kind)
        await waitFor(.running(driver.kind))
    }

    func test_beginCollectionFailure_endsAndDiscardsSession_thenCanRestart() async {
        let failing = FakeSessionDriver(kind: .running)
        failing.beginError = StubError()
        store.pendingSessions = [failing]

        await model.start(.running)
        await waitFor(.failed(.startFailed))
        XCTAssertEqual(failing.calls, ["startActivity", "beginCollection", "end", "discardWorkout"])
        XCTAssertEqual(controller.elapsedTime(at: Date()), 0, "남은 세션이 없어야 함")

        model.dismiss()
        let next = FakeSessionDriver(kind: .running)
        next.elapsed = 5
        await startRunning(next)
        XCTAssertEqual(controller.elapsedTime(at: Date()), 5)
        XCTAssertEqual(store.madeSessions, 2)
    }

    func test_sharingDenied_blocksStartBeforeCreatingSession() async {
        store.isWorkoutSharingDenied = true
        await model.start(.walking)
        XCTAssertEqual(model.phase, .failed(.authorizationFailed))
        XCTAssertEqual(store.authorizationRequests, 1)
        XCTAssertEqual(store.madeSessions, 0)
    }

    func test_fullSession_pauseResumeEnd_savesAndShowsSummary() async {
        let driver = FakeSessionDriver(kind: .cycling)
        driver.metrics = WorkoutMetrics(heartRate: 130, averageHeartRate: 125, activeCalories: 80, distanceMeters: 4_000)
        driver.elapsed = 600
        await startRunning(driver)

        driver.emit(.collectedData)
        for _ in 0..<50 where model.metrics != driver.metrics { await Task.yield() }
        XCTAssertEqual(model.metrics, driver.metrics)

        model.pause()
        await waitFor(.paused(.cycling))
        model.resume()
        await waitFor(.running(.cycling))
        model.end()
        let start = try! XCTUnwrap(driver.startDate)
        let expected = WorkoutSummary(kind: .cycling, startDate: start, endDate: start.addingTimeInterval(600), activeDuration: 600, metrics: driver.metrics)
        await waitFor(.ended(expected))
        XCTAssertEqual(Array(driver.calls.suffix(3)), ["end", "endCollection", "finishWorkout"])
        XCTAssertFalse(driver.calls.contains("discardWorkout"))
        XCTAssertEqual(waterLocks, 0)
    }

    func test_saveFailureAfterEnd_discardsAndReportsSaveFailed() async {
        let driver = FakeSessionDriver(kind: .walking)
        driver.finishError = StubError()
        await startRunning(driver)
        model.end()
        await waitFor(.failed(.saveFailed))
        XCTAssertEqual(driver.calls.last, "discardWorkout")
    }

    func test_errorDuringWorkout_triesPartialSaveFirst() async {
        let driver = FakeSessionDriver(kind: .running)
        driver.elapsed = 120
        await startRunning(driver)
        driver.emit(.failed)
        let start = try! XCTUnwrap(driver.startDate)
        await waitFor(.ended(WorkoutSummary(kind: .running, startDate: start, endDate: start.addingTimeInterval(120), activeDuration: 120, metrics: .empty)))
        XCTAssertEqual(Array(driver.calls.suffix(2)), ["endCollection", "finishWorkout"])
        XCTAssertFalse(driver.calls.contains("discardWorkout"))
    }

    func test_errorDuringWorkout_partialSaveFails_discardsAndReportsInterrupted() async {
        let driver = FakeSessionDriver(kind: .running)
        driver.endCollectionError = StubError()
        await startRunning(driver)
        driver.emit(.failed)
        await waitFor(.failed(.interrupted))
        XCTAssertEqual(Array(driver.calls.suffix(2)), ["endCollection", "discardWorkout"])
    }

    func test_errorBeforeStart_discardsAndReportsStartFailed() async {
        let driver = FakeSessionDriver(kind: .other, state: .running, startDate: nil)
        store.recoverable = driver
        await controller.recoverActiveSession()
        await waitFor(.running(.other))
        driver.emit(.failed)
        await waitFor(.failed(.startFailed))
        XCTAssertEqual(driver.calls, ["discardWorkout"])
    }

    func test_recovery_running() async {
        store.recoverable = FakeSessionDriver(kind: .walking, state: .running, startDate: Date())
        await controller.recoverActiveSession()
        XCTAssertEqual(model.phase, .running(.walking))
    }

    func test_recovery_paused_thenResumeAndEnd() async {
        let driver = FakeSessionDriver(kind: .strengthTraining, state: .paused, startDate: Date())
        store.recoverable = driver
        await controller.recoverActiveSession()
        XCTAssertEqual(model.phase, .paused(.strengthTraining))
        model.resume()
        await waitFor(.running(.strengthTraining))
    }

    func test_recovery_withoutActiveSession_staysIdle() async {
        await controller.recoverActiveSession()
        XCTAssertEqual(model.phase, .idle)
    }

    func test_swimming_enablesWaterLock() async {
        await startRunning(FakeSessionDriver(kind: .swimming))
        XCTAssertEqual(waterLocks, 1)
    }
}

// MARK: - iPhone 동기화 칼로리 절단 경계

final class AppleHealthKitProviderCalorieTests: XCTestCase {
    private struct ServerView {
        let calories: Decimal
        let spanSeconds: Int
        /// 서버 `HealthActivitySyncValidator.exceedsCaloriesPerMinute`의 반대: calories × 60 ≤ 20 × span초.
        var passes: Bool { calories * 60 <= Decimal(20 * spanSeconds) }
    }

    /// iPhone과 같은 인코딩(JSONEncoder + 초 단위 ISO-8601)으로 보낸 뒤 서버가 보는 값을 재현한다.
    private func serverView(of dto: HealthActivityDTO) throws -> ServerView {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(dto)) as? [String: Any])
        let formatter = ISO8601DateFormatter()
        let start = try XCTUnwrap(formatter.date(from: try XCTUnwrap(json["startedAt"] as? String)))
        let end = try XCTUnwrap(formatter.date(from: try XCTUnwrap(json["endedAt"] as? String)))
        let calories = try XCTUnwrap(json["calories"] as? NSNumber)
        return ServerView(
            calories: try XCTUnwrap(Decimal(string: calories.stringValue)),
            spanSeconds: Int(end.timeIntervalSince(start))
        )
    }

    private func dto(calories: Double, start: Date, end: Date) -> HealthActivityDTO {
        AppleHealthKitProvider.workoutActivity(
            workoutType: .running,
            activeDuration: end.timeIntervalSince(start),
            calories: calories,
            distanceMeters: nil,
            startedAt: start,
            endedAt: end
        )
    }

    func test_caloriesJustBelowAndAboveCap_passServerRule_withTruncatedTimestamps() throws {
        // 초 이하 자릿수가 잘리면서 서버 구간이 실제보다 짧아지는 조합을 포함한다.
        let cases: [(startFraction: Double, length: Double)] = [
            (0.1, 1799.9), (0.9, 1800.2), (0.0, 1800), (0.5, 60.4), (0.99, 61.0), (0.2, 3599.8),
        ]
        for (fraction, length) in cases {
            let start = Date(timeIntervalSince1970: 1_790_000_000 + fraction)
            let end = start.addingTimeInterval(length)
            let cap = WorkoutSyncRules.calorieCap(start: start, end: end)
            for calories in [cap - 0.05, cap, cap + 0.05, cap + 50] {
                let view = try serverView(of: dto(calories: calories, start: start, end: end))
                XCTAssertTrue(view.passes, "start+\(fraction)s, \(length)s, \(calories)kcal → \(view)")
            }
        }
    }

    func test_caloriesWithinCap_areSentUnchangedExceptTenthFloor() throws {
        let start = Date(timeIntervalSince1970: 1_790_000_000.4)
        let end = start.addingTimeInterval(1800)
        XCTAssertEqual(dto(calories: 250.46, start: start, end: end).calories, 250.4)
    }

    func test_rawCaloriesAboveServerLimit_wouldBeSkippedWithoutClamp() throws {
        let start = Date(timeIntervalSince1970: 1_790_000_000.1)
        let end = start.addingTimeInterval(1799.9)
        let clamped = try serverView(of: dto(calories: 700, start: start, end: end))
        XCTAssertTrue(clamped.passes)
        XCTAssertFalse(ServerView(calories: 700, spanSeconds: clamped.spanSeconds).passes)
    }
}
