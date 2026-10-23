import Foundation
import HealthKit
import OSLog

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
        try await load(day: Date())
    }

    func load(day: Date) async throws -> DailyHealthSnapshot {
        guard isAvailable else {
            throw HealthDataError.unavailable
        }
        let window = try Self.dayWindow(for: day, now: Date(), calendar: calendar)
        async let steps = fetchSteps(in: window)
        async let workouts = fetchWorkouts(in: window)
        async let sleep = fetchSleep(in: window)
        return try await DailyHealthSnapshot(
            date: window.snapshotDate,
            steps: steps,
            workouts: workouts,
            sleep: sleep
        )
    }

    /// 하루치 데이터를 읽는 구간.
    ///
    /// - 걸음·운동: `[그날 0시, end)`. `end`는 오늘이면 지금, 지난 날이면 다음 날 0시다.
    /// - 수면: `[그날 0시 - 12시간, end]`와 겹치면서 `end` 이전에 깬 수면. 서버는 수면을 깬 시각의 날짜로 보므로,
    ///   지난 날을 다시 읽을 때 자정을 넘겨 오늘 아침에 깬 수면은 그날이 아니라 오늘 몫으로 남긴다.
    ///   오늘은 `end`가 지금이라 깬 시각 조건이 기존 동작(`loadToday`)과 같다.
    /// 즉 지난 날 구간은 "그날 마지막 순간에 `loadToday`를 불렀다면 보냈을 데이터"와 같다.
    struct DayWindow: Equatable {
        let start: Date
        let end: Date
        let sleepStart: Date
        /// 요청 날짜(`yyyy-MM-dd`)를 만들 시각. 오늘이면 지금, 지난 날이면 그날 0시.
        let snapshotDate: Date
    }

    static func dayWindow(for day: Date, now: Date, calendar: Calendar) throws -> DayWindow {
        let start = calendar.startOfDay(for: day)
        // 서버는 미래 날짜의 걸음 수를 거절한다.
        guard start <= now,
              let nextStart = calendar.date(byAdding: .day, value: 1, to: start),
              let sleepStart = calendar.date(byAdding: .hour, value: -12, to: start) else {
            throw HealthDataError.invalidData
        }
        let isToday = nextStart > now
        return DayWindow(
            start: start,
            end: isToday ? now : nextStart,
            sleepStart: sleepStart,
            snapshotDate: isToday ? now : start
        )
    }

    private func fetchSteps(in window: DayWindow) async throws -> Int {
        guard let type = HKQuantityType.quantityType(forIdentifier: .stepCount) else {
            throw HealthDataError.invalidData
        }
        let predicate = HKQuery.predicateForSamples(withStart: window.start, end: window.end, options: .strictStartDate)

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

    private func fetchWorkouts(in window: DayWindow) async throws -> [HealthActivityDTO] {
        let predicate = HKQuery.predicateForSamples(withStart: window.start, end: window.end, options: .strictStartDate)
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

        return Self.workoutActivity(
            workoutType: mapWorkoutType(workout.workoutActivityType),
            activeDuration: workout.duration,
            calories: calories,
            distanceMeters: workout.totalDistance?.doubleValue(for: .meter()),
            startedAt: workout.startDate,
            endedAt: workout.endDate
        )
    }

    /// HealthKit 운동을 서버 동기화 항목으로 만든다(HKWorkout 없이 테스트할 수 있게 값만 받는다).
    ///
    /// 칼로리는 서버의 분당 상한(`calories × 60 ≤ 20 × 구간 초`)을 넘으면 잘라서 보낸다.
    /// 서버는 상한을 넘는 운동을 통째로 건너뛰어(0 XP) 시간·종목까지 잃는다. 센서 이상값이나 다른 앱이 쓴
    /// 과대 칼로리 때문에 운동 전체를 버리는 것보다 상한까지만 인정하는 편이 낫다고 판단했다.
    /// 절단은 드문 일이므로 원인을 추적할 수 있게 디버그 로그를 남긴다.
    static func workoutActivity(
        workoutType: WorkoutType,
        activeDuration: TimeInterval,
        calories: Double?,
        distanceMeters: Double?,
        startedAt: Date,
        endedAt: Date
    ) -> HealthActivityDTO {
        let syncedCalories = WorkoutSyncRules.clampedCalories(calories, start: startedAt, end: endedAt)
        if let calories, let syncedCalories, calories > WorkoutSyncRules.calorieCap(start: startedAt, end: endedAt) {
            logger.debug(
                "운동 칼로리를 서버 상한으로 잘랐어요: \(calories, privacy: .public)kcal → \(syncedCalories, privacy: .public)kcal (\(workoutType.rawValue, privacy: .public), \(endedAt.timeIntervalSince(startedAt), privacy: .public)s)"
            )
        }
        return HealthActivityDTO(
            type: .workout,
            workoutType: workoutType,
            durationMinutes: WorkoutSyncRules.durationMinutes(activeDuration: activeDuration),
            calories: syncedCalories,
            distanceMeters: distanceMeters,
            startedAt: startedAt,
            endedAt: endedAt
        )
    }

    private static let logger = Logger(subsystem: "io.github.seongkyulim.wellnessgame", category: "HealthSync")

    /// 종목 매핑은 Watch 운동 기록과 같은 `WorkoutKind`를 쓴다.
    private func mapWorkoutType(_ type: HKWorkoutActivityType) -> WorkoutType {
        WorkoutType(rawValue: WorkoutKind(activityType: type).serverType) ?? .other
    }

    private func fetchSleep(in window: DayWindow) async throws -> HealthActivityDTO? {
        guard let type = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else {
            throw HealthDataError.invalidData
        }
        let predicate = HKQuery.predicateForSamples(withStart: window.sleepStart, end: window.end, options: [])
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
                let sleepSamples = (samples as? [HKCategorySample] ?? [])
                    .map { SleepSample(value: $0.value, start: $0.startDate, end: $0.endDate) }
                continuation.resume(returning: Self.sleepActivity(from: sleepSamples, in: window))
            }
            manager.store.execute(query)
        }
    }

    /// HKCategorySample 없이 테스트할 수 있게 수면 샘플의 값만 담는다.
    struct SleepSample {
        let value: Int
        let start: Date
        let end: Date
    }

    /// 수면 샘플을 하루치 수면 항목으로 만든다. 잠든 상태 샘플 중 `window.end` 이전에 깬 것만 합친다
    /// (서버는 수면을 깬 시각의 날짜로 본다. `DayWindow` 참고).
    static func sleepActivity(from samples: [SleepSample], in window: DayWindow) -> HealthActivityDTO? {
        let asleep = samples.filter { isAsleepValue($0.value) && $0.end <= window.end }
        guard !asleep.isEmpty else {
            return nil
        }
        let intervals = mergeIntervals(asleep.map { ($0.start, $0.end) })
        let seconds = intervals.reduce(0.0) { $0 + $1.1.timeIntervalSince($1.0) }
        let minutes = Int((seconds / 60).rounded())
        return HealthActivityDTO(
            type: .sleep,
            sleepMinutes: minutes,
            sleepScore: sleepScore(minutes: minutes),
            startedAt: intervals.first?.0,
            endedAt: intervals.last?.1
        )
    }

    private static func isAsleepValue(_ value: Int) -> Bool {
        let asleepValues: Set<Int> = [
            HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
            HKCategoryValueSleepAnalysis.asleepCore.rawValue,
            HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
            HKCategoryValueSleepAnalysis.asleepREM.rawValue
        ]
        return asleepValues.contains(value)
    }

    private static func mergeIntervals(_ intervals: [(Date, Date)]) -> [(Date, Date)] {
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

    private static func sleepScore(minutes: Int) -> Int {
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
