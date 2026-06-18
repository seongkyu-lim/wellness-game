import AuthenticationServices
import Combine
import Foundation

enum LoginProvider: String {
    case apple
    case google
    case kakao

    var displayName: String {
        switch self {
        case .apple: "Apple"
        case .google: "Google"
        case .kakao: "Kakao"
        }
    }
}

@MainActor
final class UserSession: ObservableObject {
    @Published private(set) var provider: LoginProvider?
    @Published private(set) var providerUserIdentifier: String?
    @Published private(set) var displayName: String?
    @Published private(set) var statusMessage = "로그인하지 않아도 모든 기본 기능을 사용할 수 있습니다."

    private enum Key {
        static let guestIdentifier = "auth.guestIdentifier"
        static let provider = "auth.provider"
        static let providerUserIdentifier = "auth.providerUserIdentifier"
        static let displayName = "auth.displayName"

        // Migration keys used by the first Apple-only session implementation.
        static let legacyAppleUserIdentifier = "auth.appleUserIdentifier"
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

        let storedProvider = defaults.string(forKey: Key.provider).flatMap(LoginProvider.init(rawValue:))
        let storedIdentifier = defaults.string(forKey: Key.providerUserIdentifier)
        let legacyAppleIdentifier = defaults.string(forKey: Key.legacyAppleUserIdentifier)

        if let storedProvider, let storedIdentifier {
            provider = storedProvider
            providerUserIdentifier = storedIdentifier
        } else if let legacyAppleIdentifier {
            provider = .apple
            providerUserIdentifier = legacyAppleIdentifier
            defaults.set(LoginProvider.apple.rawValue, forKey: Key.provider)
            defaults.set(legacyAppleIdentifier, forKey: Key.providerUserIdentifier)
            defaults.removeObject(forKey: Key.legacyAppleUserIdentifier)
        }

        displayName = defaults.string(forKey: Key.displayName)

        if let provider {
            statusMessage = "\(provider.displayName) 계정으로 로그인되어 있습니다."
            if provider == .apple {
                Task { await refreshAppleCredentialState() }
            }
        }
    }

    var userId: String {
        if let provider, let providerUserIdentifier {
            return "\(provider.rawValue):\(providerUserIdentifier)"
        }
        return "guest:\(guestIdentifier)"
    }

    var isSignedIn: Bool {
        provider != nil && providerUserIdentifier != nil
    }

    var accountLabel: String {
        if isSignedIn {
            return displayName ?? "\(provider?.displayName ?? "") 사용자"
        }
        return "게스트"
    }

    func configureAppleRequest(_ request: ASAuthorizationAppleIDRequest) {
        request.requestedScopes = [.fullName, .email]
    }

    func handleAppleResult(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case let .success(authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                statusMessage = "Apple 로그인 정보를 확인하지 못했습니다."
                return
            }
            saveAppleCredential(credential)
        case let .failure(error):
            if let authorizationError = error as? ASAuthorizationError,
               authorizationError.code == .canceled {
                statusMessage = "로그인을 취소했습니다. 게스트로 계속 이용할 수 있습니다."
            } else {
                statusMessage = "Apple 로그인에 실패했습니다: \(error.localizedDescription)"
            }
        }
    }

    func signIn(provider: LoginProvider, userIdentifier: String, displayName: String?) {
        self.provider = provider
        providerUserIdentifier = userIdentifier
        self.displayName = displayName

        defaults.set(provider.rawValue, forKey: Key.provider)
        defaults.set(userIdentifier, forKey: Key.providerUserIdentifier)
        if let displayName, !displayName.isEmpty {
            defaults.set(displayName, forKey: Key.displayName)
        } else {
            defaults.removeObject(forKey: Key.displayName)
        }
        statusMessage = "\(provider.displayName) 계정으로 로그인했습니다."
    }

    func signOut(message: String = "로그아웃했습니다. 게스트 데이터로 계속 이용합니다.") {
        provider = nil
        providerUserIdentifier = nil
        displayName = nil
        defaults.removeObject(forKey: Key.provider)
        defaults.removeObject(forKey: Key.providerUserIdentifier)
        defaults.removeObject(forKey: Key.displayName)
        defaults.removeObject(forKey: Key.legacyAppleUserIdentifier)
        statusMessage = message
    }

    func updateStatus(_ message: String) {
        statusMessage = message
    }

    func refreshAppleCredentialState() async {
        guard provider == .apple, let providerUserIdentifier else {
            return
        }

        do {
            let state = try await appleIDProvider.credentialState(forUserID: providerUserIdentifier)
            switch state {
            case .authorized:
                break
            case .revoked, .notFound:
                signOut(message: "Apple 로그인 상태가 만료되어 게스트로 전환했습니다.")
            case .transferred:
                statusMessage = "Apple 계정 이전 상태입니다. 다시 로그인해 주세요."
            @unknown default:
                statusMessage = "Apple 로그인 상태를 확인하지 못했습니다."
            }
        } catch {
            statusMessage = "Apple 로그인 상태 확인에 실패했습니다. 현재 세션을 유지합니다."
        }
    }

    private func saveAppleCredential(_ credential: ASAuthorizationAppleIDCredential) {
        let formatter = PersonNameComponentsFormatter()
        let providedName = credential.fullName.map { formatter.string(from: $0) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = [providedName, credential.email, displayName]
            .compactMap { $0 }
            .first { !$0.isEmpty }

        signIn(provider: .apple, userIdentifier: credential.user, displayName: resolvedName)
    }
}
