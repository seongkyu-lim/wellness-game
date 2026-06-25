import Foundation

enum HealthActivityType: String, Encodable {
    case steps = "STEPS"
    case workout = "WORKOUT"
    case sleep = "SLEEP"
}

enum WorkoutType: String, Encodable {
    case swimming = "SWIMMING"
    case running = "RUNNING"
    case walking = "WALKING"
    case cycling = "CYCLING"
    case strengthTraining = "STRENGTH_TRAINING"
    case other = "OTHER"
}

struct HealthActivityDTO: Identifiable, Encodable {
    let id: UUID
    let type: HealthActivityType
    var workoutType: WorkoutType?
    var durationMinutes: Int?
    var calories: Double?
    var distanceMeters: Double?
    var steps: Int?
    var sleepMinutes: Int?
    var sleepScore: Int?
    var startedAt: Date?
    var endedAt: Date?

    init(
        id: UUID = UUID(),
        type: HealthActivityType,
        workoutType: WorkoutType? = nil,
        durationMinutes: Int? = nil,
        calories: Double? = nil,
        distanceMeters: Double? = nil,
        steps: Int? = nil,
        sleepMinutes: Int? = nil,
        sleepScore: Int? = nil,
        startedAt: Date? = nil,
        endedAt: Date? = nil
    ) {
        self.id = id
        self.type = type
        self.workoutType = workoutType
        self.durationMinutes = durationMinutes
        self.calories = calories
        self.distanceMeters = distanceMeters
        self.steps = steps
        self.sleepMinutes = sleepMinutes
        self.sleepScore = sleepScore
        self.startedAt = startedAt
        self.endedAt = endedAt
    }

    enum CodingKeys: String, CodingKey {
        case type
        case workoutType
        case durationMinutes
        case calories
        case distanceMeters
        case steps
        case sleepMinutes
        case sleepScore
        case startedAt
        case endedAt
    }
}

struct DailyHealthSnapshot {
    let date: Date
    let steps: Int
    let workouts: [HealthActivityDTO]
    let sleep: HealthActivityDTO?

    var activities: [HealthActivityDTO] {
        var result = [HealthActivityDTO(type: .steps, steps: steps)]
        result.append(contentsOf: workouts)
        if let sleep {
            result.append(sleep)
        }
        return result
    }
}

struct HealthActivitySyncRequest: Encodable {
    let userId: String
    let date: String
    let activities: [HealthActivityDTO]
}
