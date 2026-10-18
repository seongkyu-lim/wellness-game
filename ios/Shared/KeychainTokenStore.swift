import Foundation
import Security

/// 서버가 발급한 액세스 토큰 저장소. 테스트에서는 메모리 구현으로 대체한다.
protocol AccessTokenStore: Sendable {
    func loadToken() -> String?
    func saveToken(_ token: String) throws
    func deleteToken()
}

enum KeychainError: LocalizedError, Equatable {
    case unexpectedStatus(OSStatus)
    case invalidData

    var errorDescription: String? {
        switch self {
        case let .unexpectedStatus(status):
            return String(localized: "로그인 정보를 기기에 저장하지 못했습니다. (Keychain \(status))")
        case .invalidData:
            return String(localized: "로그인 정보를 기기에 저장하지 못했습니다.")
        }
    }
}

/// Keychain(kSecClassGenericPassword)에 액세스 토큰을 보관한다.
///
/// 백그라운드 자동 동기화가 잠금 상태에서도 토큰을 읽을 수 있도록
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`를 사용한다.
/// ThisDeviceOnly이므로 백업·기기 이전으로 토큰이 복제되지 않는다.
struct KeychainTokenStore: AccessTokenStore {
    let service: String
    let account: String

    init(
        service: String = (Bundle.main.bundleIdentifier ?? "WellnessGame") + ".auth",
        account: String = "accessToken"
    ) {
        self.service = service
        self.account = account
    }

    func loadToken() -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let token = String(data: data, encoding: .utf8),
              !token.isEmpty else {
            return nil
        }
        return token
    }

    func saveToken(_ token: String) throws {
        guard !token.isEmpty, let data = token.data(using: .utf8) else {
            throw KeychainError.invalidData
        }

        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var addQuery = baseQuery
            addQuery.merge(attributes) { _, new in new }
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainError.unexpectedStatus(addStatus)
            }
        default:
            throw KeychainError.unexpectedStatus(updateStatus)
        }
    }

    func deleteToken() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
