import Foundation

struct MockHealthDataProvider: HealthDataProvider {
    var isAvailable: Bool {
        true
    }

    func requestAuthorization() async throws {
    }

    func loadToday() async throws -> DailyHealthSnapshot {
        try await load(day: Date())
    }

    /// 지난 날은 같은 값을 그날 0시 기준으로 옮겨 돌려준다.
    func load(day: Date) async throws -> DailyHealthSnapshot {
        let calendar = Calendar.current
        let now = Date()
        let dayStart = calendar.startOfDay(for: day)
        guard dayStart <= now else {
            throw HealthDataError.invalidData
        }
        let isToday = calendar.isDate(day, inSameDayAs: now)
        let workoutStart = calendar.date(byAdding: .hour, value: 7, to: dayStart) ?? now
        let workoutEnd = calendar.date(byAdding: .minute, value: 45, to: workoutStart) ?? now
        let sleepStart = calendar.date(byAdding: .hour, value: -2, to: dayStart) ?? now
        let sleepEnd = calendar.date(byAdding: .hour, value: 6, to: dayStart) ?? now

        return DailyHealthSnapshot(
            date: isToday ? now : dayStart,
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

