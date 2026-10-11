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
