import AuthenticationServices
import Combine
import Foundation

@MainActor
final class UserSession: ObservableObject {
    @Published private(set) var appleUserIdentifier: String?
    @Published private(set) var displayName: String?
    @Published private(set) var statusMessage = "로그인하지 않아도 모든 기본 기능을 사용할 수 있습니다."

    private enum Key {
        static let guestIdentifier = "auth.guestIdentifier"
        static let appleUserIdentifier = "auth.appleUserIdentifier"
        static let displayName = "auth.displayName"
    }

    private let defaults: UserDefaults
    private let appleIDProvider: ASAuthorizationAppleIDProvider
    private let guestIdentifier: String

    init(
        defaults: UserDefaults = .standard,
        appleIDProvider: ASAuthorizationAppleIDProvider = ASAuthorizationAppleIDProvider()
    ) {
        self.defaults = defaults
        self.appleIDProvider = appleIDProvider

        if let storedGuestIdentifier = defaults.string(forKey: Key.guestIdentifier) {
            guestIdentifier = storedGuestIdentifier
        } else {
            let newGuestIdentifier = UUID().uuidString.lowercased()
            defaults.set(newGuestIdentifier, forKey: Key.guestIdentifier)
            guestIdentifier = newGuestIdentifier
        }

        appleUserIdentifier = defaults.string(forKey: Key.appleUserIdentifier)
        displayName = defaults.string(forKey: Key.displayName)

        if appleUserIdentifier != nil {
            statusMessage = "Apple 계정으로 로그인되어 있습니다."
            Task { await refreshCredentialState() }
        }
    }

    var userId: String {
        if let appleUserIdentifier {
            return "apple:\(appleUserIdentifier)"
        }
        return "guest:\(guestIdentifier)"
    }

    var isSignedIn: Bool {
        appleUserIdentifier != nil
    }

    var accountLabel: String {
        if isSignedIn {
            return displayName ?? "Apple 사용자"
        }
        return "게스트"
    }

    func configure(_ request: ASAuthorizationAppleIDRequest) {
        request.requestedScopes = [.fullName, .email]
    }

    func handle(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case let .success(authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                statusMessage = "Apple 로그인 정보를 확인하지 못했습니다."
                return
            }
            save(credential)
        case let .failure(error):
            if let authorizationError = error as? ASAuthorizationError,
               authorizationError.code == .canceled {
                statusMessage = "로그인을 취소했습니다. 게스트로 계속 이용할 수 있습니다."
            } else {
                statusMessage = "Apple 로그인에 실패했습니다: \(error.localizedDescription)"
            }
        }
    }

    func signOut() {
        appleUserIdentifier = nil
        displayName = nil
        defaults.removeObject(forKey: Key.appleUserIdentifier)
        defaults.removeObject(forKey: Key.displayName)
        statusMessage = "로그아웃했습니다. 게스트 데이터로 계속 이용합니다."
    }

    func refreshCredentialState() async {
        guard let appleUserIdentifier else {
            return
        }

        do {
            let state = try await appleIDProvider.credentialState(forUserID: appleUserIdentifier)
            switch state {
            case .authorized:
                break
            case .revoked, .notFound:
                signOut()
                statusMessage = "Apple 로그인 상태가 만료되어 게스트로 전환했습니다."
            case .transferred:
                statusMessage = "Apple 계정 이전 상태입니다. 다시 로그인해 주세요."
            @unknown default:
                statusMessage = "Apple 로그인 상태를 확인하지 못했습니다."
            }
        } catch {
            statusMessage = "Apple 로그인 상태 확인에 실패했습니다. 현재 세션을 유지합니다."
        }
    }

    private func save(_ credential: ASAuthorizationAppleIDCredential) {
        appleUserIdentifier = credential.user
        defaults.set(credential.user, forKey: Key.appleUserIdentifier)

        let formatter = PersonNameComponentsFormatter()
        let providedName = credential.fullName.map { formatter.string(from: $0) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = [providedName, credential.email, displayName]
            .compactMap { $0 }
            .first { !$0.isEmpty }

        displayName = resolvedName
        if let resolvedName {
            defaults.set(resolvedName, forKey: Key.displayName)
        }
        statusMessage = "Apple 계정으로 로그인했습니다."
    }
}
