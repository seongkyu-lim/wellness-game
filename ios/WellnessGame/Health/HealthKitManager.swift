import Foundation
import HealthKit

final class HealthKitManager {
    let store = HKHealthStore()

    var isAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    var readTypes: Set<HKObjectType> {
        let identifiers: [HKQuantityTypeIdentifier] = [
            .stepCount,
            .activeEnergyBurned,
            .heartRate
        ]
        var types = Set<HKObjectType>(identifiers.compactMap(HKObjectType.quantityType(forIdentifier:)))
        types.insert(HKObjectType.workoutType())
        if let sleep = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) {
            types.insert(sleep)
        }
        return types
    }

    func requestAuthorization() async throws {
        guard isAvailable else {
            throw HealthDataError.unavailable
        }
        try await store.requestAuthorization(toShare: [], read: readTypes)
    }
}
