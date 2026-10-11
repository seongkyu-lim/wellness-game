import Foundation
import HealthKit

/// `HKWorkoutSession` + `HKLiveWorkoutBuilder`로 운동을 기록하고 끝나면 HealthKit에 저장한다.
/// 세션이 도는 동안에는 `workout-processing` 백그라운드 모드로 화면이 꺼져도 계속 기록된다.
@MainActor
final class HealthKitWorkoutController: NSObject, WorkoutSessionControlling {
    var onEvent: ((WorkoutEvent) -> Void)?
    var onMetrics: ((WorkoutMetrics) -> Void)?

    private let store: HKHealthStore
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var kind: WorkoutKind = .other

    init(store: HKHealthStore = HKHealthStore()) {
        self.store = store
    }

    var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    /// 운동·심박·활동 에너지·거리. 빌더가 수집한 샘플을 운동과 함께 저장하려면 쓰기 권한도 필요하다.
    static var sampleTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = [HKObjectType.workoutType()]
        let identifiers: [HKQuantityTypeIdentifier] = [
            .heartRate, .activeEnergyBurned, .distanceWalkingRunning, .distanceCycling, .distanceSwimming,
        ]
        for identifier in identifiers {
            types.insert(HKQuantityType(identifier))
        }
        return types
    }

    func requestAuthorization() async throws {
        let types = Self.sampleTypes
        try await store.requestAuthorization(toShare: types, read: Set(types.map { $0 as HKObjectType }))
    }

    func start(_ kind: WorkoutKind) async throws {
        let configuration = kind.makeConfiguration()
        let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
        let builder = session.associatedWorkoutBuilder()
        builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
        attach(session: session, builder: builder, kind: kind)

        let startDate = Date()
        session.startActivity(with: startDate)
        try await builder.beginCollection(at: startDate)
    }

    /// 앱이 운동 중에 종료됐다가 다시 실행되면 진행 중이던 세션을 이어받는다.
    func recoverActiveSession() async {
        guard session == nil, let session = try? await store.recoverActiveWorkoutSession() else { return }
        let kind = WorkoutKind(activityType: session.workoutConfiguration.activityType)
        attach(session: session, builder: session.associatedWorkoutBuilder(), kind: kind)
        onEvent?(.sessionRecovered(kind))
        if session.state == .paused {
            onEvent?(.sessionPaused)
        }
    }

    func pause() {
        session?.pause()
    }

    func resume() {
        session?.resume()
    }

    func end() {
        session?.end()
    }

    func elapsedTime(at date: Date) -> TimeInterval {
        builder?.elapsedTime(at: date) ?? 0
    }

    // MARK: - Private

    private func attach(session: HKWorkoutSession, builder: HKLiveWorkoutBuilder, kind: WorkoutKind) {
        session.delegate = self
        builder.delegate = self
        self.session = session
        self.builder = builder
        self.kind = kind
    }

    private func finish(at endDate: Date) async {
        guard let builder else { return }
        do {
            try await builder.endCollection(at: endDate)
            let workout = try await builder.finishWorkout()
            let metrics = Self.metrics(from: builder, kind: kind)
            let summary = WorkoutSummary(
                kind: kind,
                startDate: workout?.startDate ?? builder.startDate ?? endDate,
                endDate: workout?.endDate ?? endDate,
                activeDuration: workout?.duration ?? builder.elapsedTime,
                metrics: metrics
            )
            reset()
            onEvent?(.sessionFinished(summary))
        } catch {
            reset()
            onEvent?(.sessionFailed(.saveFailed))
        }
    }

    private func reset() {
        session = nil
        builder = nil
    }

    nonisolated private static func metrics(from builder: HKLiveWorkoutBuilder, kind: WorkoutKind) -> WorkoutMetrics {
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let heartRate = builder.statistics(for: HKQuantityType(.heartRate))
        let energy = builder.statistics(for: HKQuantityType(.activeEnergyBurned))
        let distance = kind.distanceTypeIdentifier.flatMap { builder.statistics(for: HKQuantityType($0)) }
        return WorkoutMetrics(
            heartRate: heartRate?.mostRecentQuantity()?.doubleValue(for: bpm),
            averageHeartRate: heartRate?.averageQuantity()?.doubleValue(for: bpm),
            activeCalories: energy?.sumQuantity()?.doubleValue(for: .kilocalorie()),
            distanceMeters: distance?.sumQuantity()?.doubleValue(for: .meter())
        )
    }
}

// MARK: - HKWorkoutSessionDelegate

extension HealthKitWorkoutController: HKWorkoutSessionDelegate {
    nonisolated func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {
        Task { @MainActor in
            guard workoutSession === self.session else { return }
            switch toState {
            case .running:
                self.onEvent?(fromState == .paused ? .sessionResumed : .sessionStarted)
            case .paused:
                self.onEvent?(.sessionPaused)
            case .ended:
                await self.finish(at: date)
            default:
                break
            }
        }
    }

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        Task { @MainActor in
            guard workoutSession === self.session else { return }
            let started = workoutSession.startDate != nil
            self.builder?.discardWorkout()
            self.reset()
            self.onEvent?(.sessionFailed(started ? .interrupted : .startFailed))
        }
    }
}

// MARK: - HKLiveWorkoutBuilderDelegate

extension HealthKitWorkoutController: HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        Task { @MainActor in
            guard workoutBuilder === self.builder else { return }
            self.onMetrics?(Self.metrics(from: workoutBuilder, kind: self.kind))
        }
    }

    nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
}
