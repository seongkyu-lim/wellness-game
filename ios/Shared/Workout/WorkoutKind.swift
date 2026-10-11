import Foundation
import HealthKit

/// 서버 `WorkoutType`과 1:1로 대응하는 운동 종류.
/// iPhone(HealthKit 읽기 → 서버 동기화)과 Watch(운동 기록 → HealthKit 저장)가 같은 매핑을 쓰도록 공용으로 둔다.
enum WorkoutKind: String, CaseIterable, Identifiable, Sendable {
    case running = "RUNNING"
    case walking = "WALKING"
    case cycling = "CYCLING"
    case swimming = "SWIMMING"
    case strengthTraining = "STRENGTH_TRAINING"
    case other = "OTHER"

    var id: String { rawValue }

    /// 서버 API의 `workoutType` 값.
    var serverType: String { rawValue }

    /// 수영장 기본 레인 길이(m). Watch에서 따로 설정하지 않으면 25m로 기록한다.
    static let defaultPoolLapLengthMeters: Double = 25

    /// HealthKit 운동 종류 → 서버 종류. 근력 운동은 전통·기능성 모두 STRENGTH_TRAINING으로 묶는다.
    init(activityType: HKWorkoutActivityType) {
        switch activityType {
        case .running:
            self = .running
        case .walking:
            self = .walking
        case .cycling:
            self = .cycling
        case .swimming:
            self = .swimming
        case .traditionalStrengthTraining, .functionalStrengthTraining:
            self = .strengthTraining
        default:
            self = .other
        }
    }

    /// Watch에서 운동을 기록할 때 쓰는 HealthKit 운동 종류. `init(activityType:)`로 되돌리면 같은 값이 된다.
    var activityType: HKWorkoutActivityType {
        switch self {
        case .running: .running
        case .walking: .walking
        case .cycling: .cycling
        case .swimming: .swimming
        case .strengthTraining: .traditionalStrengthTraining
        case .other: .other
        }
    }

    /// 실시간으로 거리를 표시·저장하는 종목의 거리 샘플 종류. 거리가 없는 종목은 nil.
    var distanceTypeIdentifier: HKQuantityTypeIdentifier? {
        switch self {
        case .running, .walking: .distanceWalkingRunning
        case .cycling: .distanceCycling
        case .swimming: .distanceSwimming
        case .strengthTraining, .other: nil
        }
    }

    var tracksDistance: Bool { distanceTypeIdentifier != nil }

    var locationType: HKWorkoutSessionLocationType {
        switch self {
        case .running, .walking, .cycling: .outdoor
        case .swimming, .strengthTraining: .indoor
        case .other: .unknown
        }
    }

    /// 운동 세션 구성. 수영은 수영장(실내)·기본 레인 길이로 설정한다(오픈워터는 지원하지 않음).
    func makeConfiguration() -> HKWorkoutConfiguration {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = activityType
        configuration.locationType = locationType
        if self == .swimming {
            configuration.swimmingLocationType = .pool
            configuration.lapLength = HKQuantity(unit: .meter(), doubleValue: Self.defaultPoolLapLengthMeters)
        }
        return configuration
    }
}
