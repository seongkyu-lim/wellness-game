import Foundation
import HealthKit

final class AppleHealthKitProvider: HealthDataProvider {
    private let manager: HealthKitManager
    private let calendar: Calendar

    init(manager: HealthKitManager = HealthKitManager(), calendar: Calendar = .current) {
        self.manager = manager
        self.calendar = calendar
    }

    var isAvailable: Bool {
        manager.isAvailable
    }

    func requestAuthorization() async throws {
        try await manager.requestAuthorization()
    }

    func loadToday() async throws -> DailyHealthSnapshot {
        guard isAvailable else {
            throw HealthDataError.unavailable
        }
        async let steps = fetchSteps()
        async let workouts = fetchWorkouts()
        async let sleep = fetchLastNightSleep()
        return try await DailyHealthSnapshot(
            date: Date(),
            steps: steps,
            workouts: workouts,
            sleep: sleep
        )
    }

    private func fetchSteps() async throws -> Int {
        guard let type = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            throw HealthDataError.invalidData
        }
        let now = Date()
        let start = calendar.startOfDay(for: now)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: now, options: .strictStartDate)

        return try await withCheckedThrowingContinuation { continuation in
            let query = HKStatisticsQuery(
                quantityType: type,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum
            ) { _, statistics, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let count = statistics?.sumQuantity()?.doubleValue(for: .count()) ?? 0
                continuation.resume(returning: Int(count.rounded()))
            }
            manager.store.execute(query)
        }
    }

    private func fetchWorkouts() async throws -> [HealthActivityDTO] {
        let now = Date()
        let start = calendar.startOfDay(for: now)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: now, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)

        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: .workoutType(),
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [sort]
            ) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let workouts = (samples as? [HKWorkout] ?? []).map(self.mapWorkout)
                continuation.resume(returning: workouts)
            }
            manager.store.execute(query)
        }
    }

    private func mapWorkout(_ workout: HKWorkout) -> HealthActivityDTO {
        let energyType = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned)
        let calories = energyType
            .flatMap { workout.statistics(for: $0)?.sumQuantity() }
            .map { $0.doubleValue(for: .kilocalorie()) }

        return HealthActivityDTO(
            type: .workout,
            workoutType: mapWorkoutType(workout.workoutActivityType),
            durationMinutes: max(1, Int((workout.duration / 60).rounded())),
            calories: calories,
            distanceMeters: workout.totalDistance?.doubleValue(for: .meter()),
            startedAt: workout.startDate,
            endedAt: workout.endDate
        )
    }

    private func mapWorkoutType(_ type: HKWorkoutActivityType) -> WorkoutType {
        switch type {
        case .swimming:
            return .swimming
        case .running:
            return .running
        case .walking:
            return .walking
        case .cycling:
            return .cycling
        case .traditionalStrengthTraining, .functionalStrengthTraining:
            return .strengthTraining
        default:
            return .other
        }
    }

    private func fetchLastNightSleep() async throws -> HealthActivityDTO? {
        guard let type = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else {
            throw HealthDataError.invalidData
        }
        let now = Date()
        let todayStart = calendar.startOfDay(for: now)
        guard let windowStart = calendar.date(byAdding: .hour, value: -12, to: todayStart) else {
            throw HealthDataError.invalidData
        }
        let predicate = HKQuery.predicateForSamples(withStart: windowStart, end: now, options: [])
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)

        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: type,
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [sort]
            ) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let asleep = (samples as? [HKCategorySample] ?? [])
                    .filter { self.isAsleepValue($0.value) }
                guard !asleep.isEmpty else {
                    continuation.resume(returning: nil)
                    return
                }

                let intervals = self.mergeIntervals(asleep.map { ($0.startDate, $0.endDate) })
                let seconds = intervals.reduce(0.0) { $0 + $1.1.timeIntervalSince($1.0) }
                let minutes = Int((seconds / 60).rounded())
                continuation.resume(returning: HealthActivityDTO(
                    type: .sleep,
                    sleepMinutes: minutes,
                    sleepScore: self.sleepScore(minutes: minutes),
                    startedAt: intervals.first?.0,
                    endedAt: intervals.last?.1
                ))
            }
            manager.store.execute(query)
        }
    }

    private func isAsleepValue(_ value: Int) -> Bool {
        let asleepValues: Set<Int> = [
            HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
            HKCategoryValueSleepAnalysis.asleepCore.rawValue,
            HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
            HKCategoryValueSleepAnalysis.asleepREM.rawValue
        ]
        return asleepValues.contains(value)
    }

    private func mergeIntervals(_ intervals: [(Date, Date)]) -> [(Date, Date)] {
        intervals.sorted { $0.0 < $1.0 }.reduce(into: []) { merged, interval in
            guard let last = merged.last else {
                merged.append(interval)
                return
            }
            if interval.0 <= last.1 {
                merged[merged.count - 1] = (last.0, max(last.1, interval.1))
            } else {
                merged.append(interval)
            }
        }
    }

    private func sleepScore(minutes: Int) -> Int {
        switch minutes {
        case 420...540:
            return 90
        case 541...:
            return 70
        case 360..<420:
            return 70
        case 300..<360:
            return 50
        default:
            return 30
        }
    }
}
