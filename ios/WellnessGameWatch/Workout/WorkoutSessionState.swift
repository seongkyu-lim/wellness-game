import Foundation

/// 운동 중 실시간 수치. HealthKit 빌더 통계에서 채우며 아직 값이 없으면 nil.
struct WorkoutMetrics: Equatable, Sendable {
    var heartRate: Double?
    var averageHeartRate: Double?
    var activeCalories: Double?
    var distanceMeters: Double?

    static let empty = WorkoutMetrics()
}

/// 종료 화면에 보여 줄 요약. HealthKit 저장 결과(또는 종료 시점 통계)로 만든다.
struct WorkoutSummary: Equatable, Sendable {
    let kind: WorkoutKind
    let startDate: Date
    let endDate: Date
    /// 일시정지를 뺀 활동 시간(초).
    let activeDuration: TimeInterval
    let activeCalories: Double?
    let averageHeartRate: Double?
    let distanceMeters: Double?

    init(
        kind: WorkoutKind,
        startDate: Date,
        endDate: Date,
        activeDuration: TimeInterval,
        metrics: WorkoutMetrics
    ) {
        let interval = max(0, endDate.timeIntervalSince(startDate))
        self.kind = kind
        self.startDate = startDate
        self.endDate = max(startDate, endDate)
        // 활동 시간은 구간보다 길 수 없다(빌더 경과 시간과 종료 시각이 어긋나는 경우 대비).
        self.activeDuration = min(max(0, activeDuration), interval)
        self.activeCalories = metrics.activeCalories.map { max(0, $0) }
        self.averageHeartRate = metrics.averageHeartRate.flatMap { $0 > 0 ? $0 : nil }
        self.distanceMeters = kind.tracksDistance ? metrics.distanceMeters.map { max(0, $0) } : nil
    }

    /// 표시용 정수 칼로리(kcal).
    var roundedCalories: Int? { activeCalories.map { Int($0.rounded()) } }
    /// 표시용 정수 평균 심박(bpm).
    var roundedAverageHeartRate: Int? { averageHeartRate.map { Int($0.rounded()) } }

    /// iPhone 앱이 다음 동기화 때 서버로 보낼 분 값(같은 규칙으로 계산).
    var syncedMinutes: Int { WorkoutSyncRules.durationMinutes(activeDuration: activeDuration) }
    /// iPhone 앱이 보낼 칼로리(서버 분당 상한으로 자름).
    var syncedCalories: Double? { WorkoutSyncRules.clampedCalories(activeCalories, start: startDate, end: endDate) }
}

/// 운동 세션의 화면 상태.
enum WorkoutPhase: Equatable {
    /// 종목 선택 전.
    case idle
    /// 권한 확인·세션 준비 중.
    case starting(WorkoutKind)
    case running(WorkoutKind)
    case paused(WorkoutKind)
    /// 종료 요청 후 HealthKit 저장 중.
    case ending(WorkoutKind)
    case ended(WorkoutSummary)
    case failed(WorkoutFailure)

    var kind: WorkoutKind? {
        switch self {
        case .starting(let kind), .running(let kind), .paused(let kind), .ending(let kind): kind
        case .ended(let summary): summary.kind
        case .idle, .failed: nil
        }
    }

    /// 세션이 살아 있는지(운동 중 화면을 보여야 하는지).
    var isActive: Bool {
        switch self {
        case .starting, .running, .paused, .ending: true
        case .idle, .ended, .failed: false
        }
    }
}

enum WorkoutFailure: Error, Equatable {
    /// 이 기기에서 HealthKit을 쓸 수 없음.
    case healthDataUnavailable
    /// 권한 요청 실패(거부는 HealthKit이 알려 주지 않으므로 저장 실패로 드러난다).
    case authorizationFailed
    /// 세션 시작 실패.
    case startFailed
    /// 종료 후 HealthKit 저장 실패.
    case saveFailed
    /// 운동 중 세션 오류로 중단됨.
    case interrupted
}

/// 세션·사용자 이벤트.
enum WorkoutEvent: Equatable {
    case startRequested(WorkoutKind)
    case sessionStarted
    /// 앱이 다시 실행되며 진행 중이던 세션을 되찾음.
    case sessionRecovered(WorkoutKind)
    case pauseRequested
    case sessionPaused
    case resumeRequested
    case sessionResumed
    case endRequested
    case sessionFinished(WorkoutSummary)
    case sessionFailed(WorkoutFailure)
    case dismissed
}

/// HealthKit에 의존하지 않는 상태 전이. 허용되지 않는 이벤트는 무시하고 nil을 돌려준다.
/// 일시정지·재개는 세션이 실제로 상태를 바꿨다고 알린 뒤(`sessionPaused`/`sessionResumed`)에만 반영한다.
enum WorkoutStateMachine {
    static func transition(from phase: WorkoutPhase, on event: WorkoutEvent) -> WorkoutPhase? {
        switch (phase, event) {
        case (.idle, .startRequested(let kind)),
             (.ended, .startRequested(let kind)),
             (.failed, .startRequested(let kind)):
            return .starting(kind)
        case (.idle, .sessionRecovered(let kind)):
            return .running(kind)
        case (.starting(let kind), .sessionStarted),
             (.paused(let kind), .sessionResumed):
            return .running(kind)
        case (.running(let kind), .sessionPaused):
            return .paused(kind)
        case (.running(let kind), .endRequested),
             (.paused(let kind), .endRequested):
            return .ending(kind)
        case (.ending, .sessionFinished(let summary)),
             (.running, .sessionFinished(let summary)),
             (.paused, .sessionFinished(let summary)):
            // 세션이 시스템에 의해 끝난 경우(예: 다른 앱이 운동을 시작)에도 저장 결과를 보여 준다.
            return .ended(summary)
        case (.starting, .sessionFailed(let failure)),
             (.running, .sessionFailed(let failure)),
             (.paused, .sessionFailed(let failure)),
             (.ending, .sessionFailed(let failure)):
            return .failed(failure)
        case (.ended, .dismissed), (.failed, .dismissed):
            return .idle
        default:
            return nil
        }
    }

    /// 사용자의 요청이 지금 상태에서 유효한지(세션에 명령을 보낼지).
    static func accepts(_ event: WorkoutEvent, in phase: WorkoutPhase) -> Bool {
        switch (phase, event) {
        case (.running, .pauseRequested), (.paused, .resumeRequested):
            return true
        default:
            return transition(from: phase, on: event) != nil
        }
    }
}
