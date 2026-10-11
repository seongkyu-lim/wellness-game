import Foundation
import HealthKit

// MARK: - HealthKit 경계

/// 세션·빌더 delegate 콜백을 도착 순서대로 담은 신호.
enum WorkoutSessionSignal: Sendable {
    case stateChanged(to: HKWorkoutSessionState, from: HKWorkoutSessionState, date: Date)
    case failed
    case collectedData
}

/// `finishWorkout()`으로 저장된 운동의 구간·활동 시간.
struct FinishedWorkout: Equatable, Sendable {
    let startDate: Date
    let endDate: Date
    let duration: TimeInterval
}

/// `HKWorkoutSession` + `HKLiveWorkoutBuilder` 한 쌍. 테스트에서는 가짜로 바꾼다.
protocol WorkoutSessionDriving: AnyObject {
    var kind: WorkoutKind { get }
    var state: HKWorkoutSessionState { get }
    /// 세션이 실제로 시작된 시각. 시작 전이면 nil.
    var startDate: Date? { get }
    /// delegate 콜백을 하나의 스트림으로 직렬화한 것. 소비자는 하나뿐이어야 한다.
    var signals: AsyncStream<WorkoutSessionSignal> { get }

    func startActivity(at date: Date)
    func pause()
    func resume()
    func end()
    func beginCollection(at date: Date) async throws
    func endCollection(at date: Date) async throws
    func finishWorkout() async throws -> FinishedWorkout?
    func discardWorkout()
    func currentMetrics() -> WorkoutMetrics
    func elapsedTime(at date: Date) -> TimeInterval
}

/// `HKHealthStore` 경계.
protocol WorkoutHealthStoring {
    var isHealthDataAvailable: Bool { get }
    /// 운동 쓰기 권한이 명시적으로 거부됐는지(거부면 기록해도 저장되지 않는다).
    var isWorkoutSharingDenied: Bool { get }
    func requestAuthorization() async throws
    func makeSession(for kind: WorkoutKind) throws -> WorkoutSessionDriving
    func recoverActiveSession() async -> WorkoutSessionDriving?
}

enum WorkoutAuthorizationError: Error {
    case sharingDenied
}

// MARK: - 컨트롤러

/// 세션 수명 주기를 관리한다. delegate 신호는 메인 액터의 단일 소비 루프에서 순서대로 처리한다.
@MainActor
final class WorkoutSessionController: WorkoutSessionControlling {
    var onEvent: ((WorkoutEvent) -> Void)?
    var onMetrics: ((WorkoutMetrics) -> Void)?

    private let store: WorkoutHealthStoring
    private let enableWaterLock: () -> Void
    private var session: WorkoutSessionDriving?
    private var consumer: Task<Void, Never>?

    init(store: WorkoutHealthStoring, enableWaterLock: @escaping () -> Void = {}) {
        self.store = store
        self.enableWaterLock = enableWaterLock
    }

    var isHealthDataAvailable: Bool { store.isHealthDataAvailable }

    /// 권한을 요청한 뒤 운동 쓰기가 거부돼 있으면 시작을 막는다(운동을 다 하고 저장에 실패해 기록을 잃지 않도록).
    func requestAuthorization() async throws {
        try await store.requestAuthorization()
        if store.isWorkoutSharingDenied {
            throw WorkoutAuthorizationError.sharingDenied
        }
    }

    func start(_ kind: WorkoutKind) async throws {
        let session = try store.makeSession(for: kind)
        attach(session)
        let startDate = Date()
        session.startActivity(at: startDate)
        do {
            try await session.beginCollection(at: startDate)
        } catch {
            // 세션이 running으로 남지 않게 끝내고 버린 뒤 다시 시작할 수 있게 한다.
            session.end()
            session.discardWorkout()
            detach()
            throw error
        }
        if kind == .swimming {
            enableWaterLock()
        }
    }

    func recoverActiveSession() async {
        guard session == nil, let session = await store.recoverActiveSession() else { return }
        attach(session)
        onEvent?(.sessionRecovered(session.kind))
        if session.state == .paused {
            onEvent?(.sessionPaused)
        }
    }

    func pause() { session?.pause() }
    func resume() { session?.resume() }
    func end() { session?.end() }

    func elapsedTime(at date: Date) -> TimeInterval {
        session?.elapsedTime(at: date) ?? 0
    }

    // MARK: - 신호 처리

    private func attach(_ session: WorkoutSessionDriving) {
        consumer?.cancel()
        self.session = session
        consumer = Task { [weak self] in
            for await signal in session.signals {
                guard let self, self.session === session else { return }
                await self.handle(signal, of: session)
            }
        }
    }

    private func detach() {
        consumer?.cancel()
        consumer = nil
        session = nil
    }

    private func handle(_ signal: WorkoutSessionSignal, of session: WorkoutSessionDriving) async {
        switch signal {
        case .stateChanged(to: .running, from: let from, date: _):
            onEvent?(from == .paused ? .sessionResumed : .sessionStarted)
        case .stateChanged(to: .paused, from: _, date: _):
            onEvent?(.sessionPaused)
        case .stateChanged(to: .ended, from: _, date: let date):
            await finish(session, at: date, failure: .saveFailed)
        case .stateChanged:
            break
        case .failed:
            if session.startDate != nil {
                // 운동 도중 오류: 지금까지 기록한 부분을 먼저 저장해 본다.
                await finish(session, at: Date(), failure: .interrupted)
            } else {
                session.discardWorkout()
                detach()
                onEvent?(.sessionFailed(.startFailed))
            }
        case .collectedData:
            onMetrics?(session.currentMetrics())
        }
    }

    private func finish(_ session: WorkoutSessionDriving, at endDate: Date, failure: WorkoutFailure) async {
        do {
            try await session.endCollection(at: endDate)
            let workout = try await session.finishWorkout()
            let summary = WorkoutSummary(
                kind: session.kind,
                startDate: workout?.startDate ?? session.startDate ?? endDate,
                endDate: workout?.endDate ?? endDate,
                activeDuration: workout?.duration ?? session.elapsedTime(at: endDate),
                metrics: session.currentMetrics()
            )
            detach()
            onEvent?(.sessionFinished(summary))
        } catch {
            session.discardWorkout()
            detach()
            onEvent?(.sessionFailed(failure))
        }
    }
}
