import Foundation

struct MockHealthDataProvider: HealthDataProvider {
    var isAvailable: Bool {
        true
    }

    func requestAuthorization() async throws {
    }

    func loadToday() async throws -> DailyHealthSnapshot {
        let calendar = Calendar.current
        let now = Date()
        let today = calendar.startOfDay(for: now)
        let workoutStart = calendar.date(byAdding: .hour, value: 7, to: today) ?? now
        let workoutEnd = calendar.date(byAdding: .minute, value: 45, to: workoutStart) ?? now
        let sleepStart = calendar.date(byAdding: .hour, value: -2, to: today) ?? now
        let sleepEnd = calendar.date(byAdding: .hour, value: 6, to: today) ?? now

        return DailyHealthSnapshot(
            date: now,
            steps: 8_500,
            workouts: [
                HealthActivityDTO(
                    type: .workout,
                    workoutType: .swimming,
                    durationMinutes: 45,
                    calories: 420,
                    distanceMeters: 1_200,
                    startedAt: workoutStart,
                    endedAt: workoutEnd
                )
            ],
            sleep: HealthActivityDTO(
                type: .sleep,
                sleepMinutes: 480,
                sleepScore: 90,
                startedAt: sleepStart,
                endedAt: sleepEnd
            )
        )
    }
}

