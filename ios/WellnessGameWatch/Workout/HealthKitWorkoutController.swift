import Foundation
import HealthKit
import WatchKit

/// 실제 HealthKit을 쓰는 운동 컨트롤러. 수영은 시작 시 방수 잠금을 켠다.
@MainActor
func makeHealthKitWorkoutController() -> WorkoutSessionController {
    WorkoutSessionController(
        store: HealthKitWorkoutStore(),
        enableWaterLock: { WKInterfaceDevice.current().enableWaterLock() }
    )
}

/// `HKHealthStore` 어댑터.
final class HealthKitWorkoutStore: WorkoutHealthStoring {
    private let store: HKHealthStore

    init(store: HKHealthStore = HKHealthStore()) {
        self.store = store
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

    var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    var isWorkoutSharingDenied: Bool {
        store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingDenied
    }

    func requestAuthorization() async throws {
        let types = Self.sampleTypes
        try await store.requestAuthorization(toShare: types, read: Set(types.map { $0 as HKObjectType }))
    }

    func makeSession(for kind: WorkoutKind) throws -> WorkoutSessionDriving {
        let session = try HKWorkoutSession(healthStore: store, configuration: kind.makeConfiguration())
        return HealthKitWorkoutSessionDriver(session: session, store: store)
    }

    func recoverActiveSession() async -> WorkoutSessionDriving? {
        guard let session = try? await store.recoverActiveWorkoutSession() else { return nil }
        return HealthKitWorkoutSessionDriver(session: session, store: store)
    }
}

/// `HKWorkoutSession`·`HKLiveWorkoutBuilder` 어댑터. 두 delegate의 콜백을 하나의 `AsyncStream`으로 모은다.
final class HealthKitWorkoutSessionDriver: NSObject, WorkoutSessionDriving, @unchecked Sendable {
    let kind: WorkoutKind
    let signals: AsyncStream<WorkoutSessionSignal>

    private let session: HKWorkoutSession
    private let builder: HKLiveWorkoutBuilder
    private let continuation: AsyncStream<WorkoutSessionSignal>.Continuation

    init(session: HKWorkoutSession, store: HKHealthStore) {
        self.session = session
        self.builder = session.associatedWorkoutBuilder()
        self.kind = WorkoutKind(activityType: session.workoutConfiguration.activityType)
        (signals, continuation) = AsyncStream.makeStream(of: WorkoutSessionSignal.self)
        super.init()
        if builder.dataSource == nil {
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: session.workoutConfiguration)
        }
        session.delegate = self
        builder.delegate = self
    }

    deinit {
        continuation.finish()
    }

    var state: HKWorkoutSessionState { session.state }
    var startDate: Date? { session.startDate }

    func startActivity(at date: Date) { session.startActivity(with: date) }
    func pause() { session.pause() }
    func resume() { session.resume() }
    func end() { session.end() }

    func beginCollection(at date: Date) async throws {
        try await builder.beginCollection(at: date)
    }

    func endCollection(at date: Date) async throws {
        try await builder.endCollection(at: date)
    }

    func finishWorkout() async throws -> FinishedWorkout? {
        try await builder.finishWorkout().map {
            FinishedWorkout(startDate: $0.startDate, endDate: $0.endDate, duration: $0.duration)
        }
    }

    func discardWorkout() {
        builder.discardWorkout()
    }

    func elapsedTime(at date: Date) -> TimeInterval {
        builder.elapsedTime(at: date)
    }

    func currentMetrics() -> WorkoutMetrics {
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

extension HealthKitWorkoutSessionDriver: HKWorkoutSessionDelegate {
    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {
        continuation.yield(.stateChanged(to: toState, from: fromState, date: date))
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        continuation.yield(.failed)
    }
}

extension HealthKitWorkoutSessionDriver: HKLiveWorkoutBuilderDelegate {
    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        continuation.yield(.collectedData)
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
}
