import Foundation

protocol HealthDataProvider {
    var isAvailable: Bool { get }
    func requestAuthorization() async throws
    func loadToday() async throws -> DailyHealthSnapshot
}

enum HealthDataError: LocalizedError {
    case unavailable
    case authorizationDenied
    case invalidData

    var errorDescription: String? {
        switch self {
        case .unavailable:
            return String(localized: "이 기기에서는 HealthKit을 사용할 수 없습니다.")
        case .authorizationDenied:
            return String(localized: "건강 데이터 읽기 권한이 필요합니다. 설정 앱에서 권한을 확인해 주세요.")
        case .invalidData:
            return String(localized: "건강 데이터를 처리하지 못했습니다.")
        }
    }
}

