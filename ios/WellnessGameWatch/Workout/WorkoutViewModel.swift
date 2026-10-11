import Foundation

/// 운동 세션을 실제로 돌리는 쪽(HealthKit 또는 테스트·데모용 가짜).
/// 세션 상태 변화와 수치는 `onEvent`·`onMetrics`로 메인 액터에서 알린다.
@MainActor
protocol WorkoutSessionControlling: AnyObject {
    var isHealthDataAvailable: Bool { get }
    var onEvent: ((WorkoutEvent) -> Void)? { get set }
    var onMetrics: ((WorkoutMetrics) -> Void)? { get set }

    /// 운동·심박·활동 에너지·거리 읽기·쓰기 권한을 요청한다(이미 결정된 경우 바로 끝난다).
    func requestAuthorization() async throws
    /// 세션을 시작한다. 실제로 시작되면 `.sessionStarted`를 보낸다.
    func start(_ kind: WorkoutKind) async throws
    func pause()
    func resume()
    /// 세션을 끝내고 HealthKit에 저장한다. 끝나면 `.sessionFinished` 또는 `.sessionFailed`를 보낸다.
    func end()
    /// 일시정지를 뺀 경과 시간.
    func elapsedTime(at date: Date) -> TimeInterval
    /// 운동 중 앱이 다시 실행됐을 때 진행 중인 세션을 이어받는다. 이어받으면 `.sessionRecovered`를 보낸다.
    func recoverActiveSession() async
}

/// Watch 운동 화면의 상태. HealthKit 호출은 `WorkoutSessionControlling`에 맡기고,
/// 화면 상태 전이는 `WorkoutStateMachine`으로만 바꾼다.
/// 서버로는 아무것도 보내지 않는다: HealthKit에 저장된 운동을 iPhone 앱이 다음 동기화 때 반영한다.
@MainActor
final class WorkoutViewModel: ObservableObject {
    @Published private(set) var phase: WorkoutPhase = .idle
    @Published private(set) var metrics: WorkoutMetrics = .empty

    private let controller: WorkoutSessionControlling

    init(controller: WorkoutSessionControlling) {
        self.controller = controller
        controller.onEvent = { [weak self] event in self?.apply(event) }
        controller.onMetrics = { [weak self] metrics in self?.metrics = metrics }
    }

    var isPaused: Bool {
        if case .paused = phase { return true }
        return false
    }

    func start(_ kind: WorkoutKind) async {
        guard apply(.startRequested(kind)) else { return }
        metrics = .empty
        guard controller.isHealthDataAvailable else {
            apply(.sessionFailed(.healthDataUnavailable))
            return
        }
        do {
            try await controller.requestAuthorization()
        } catch {
            apply(.sessionFailed(.authorizationFailed))
            return
        }
        do {
            try await controller.start(kind)
        } catch {
            apply(.sessionFailed(.startFailed))
        }
    }

    func pause() {
        guard WorkoutStateMachine.accepts(.pauseRequested, in: phase) else { return }
        controller.pause()
    }

    func resume() {
        guard WorkoutStateMachine.accepts(.resumeRequested, in: phase) else { return }
        controller.resume()
    }

    func togglePause() {
        isPaused ? resume() : pause()
    }

    func end() {
        guard apply(.endRequested) else { return }
        controller.end()
    }

    /// 종료·오류 화면을 닫고 처음(종목 선택)으로 돌아간다.
    func dismiss() {
        guard apply(.dismissed) else { return }
        metrics = .empty
    }

    func elapsedTime(at date: Date) -> TimeInterval {
        controller.elapsedTime(at: date)
    }

    @discardableResult
    private func apply(_ event: WorkoutEvent) -> Bool {
        guard let next = WorkoutStateMachine.transition(from: phase, on: event) else { return false }
        phase = next
        return true
    }
}
