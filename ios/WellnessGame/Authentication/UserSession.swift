import Combine
import Foundation

enum LoginProvider: String {
    case apple
    case google
    case kakao
    case naver
    case password

    var displayName: String {
        switch self {
        case .apple: "Apple"
        case .google: "Google"
        case .kakao: "Kakao"
        case .naver: "Naver"
        case .password: String(localized: "계정")
        }
    }

    /// 서버 토큰 발급을 지원하는 로그인 방식.
    var supportsServerToken: Bool {
        self == .google || self == .password
    }
}

/// 서버가 발급한 토큰과 사용자 ID로 로그인 상태를 관리한다.
///
/// - 토큰은 Keychain(`AccessTokenStore`), 표시용 정보는 UserDefaults에 둔다.
/// - `userId`는 서버 응답 값만 사용하며 앱이 직접 조합하지 않는다.
/// - 게스트 세션은 없다. 로그인하지 않으면 `userId == nil`이다.
@MainActor
final class UserSession: ObservableObject {
    static var signInPrompt: String { String(localized: "로그인하면 건강 데이터를 동기화하고 캐릭터를 키울 수 있어요.") }
    static var reloginMessage: String { String(localized: "로그인이 만료되었어요. 다시 로그인해 주세요.") }

    @Published private(set) var provider: LoginProvider?
    @Published private(set) var userId: String?
    @Published private(set) var displayName: String?
    @Published private(set) var statusMessage = UserSession.signInPrompt
    /// 세션이 강제로 종료됐을 때(401, 만료, 이전 버전 세션) 보여 줄 재로그인 안내.
    @Published private(set) var reloginNotice: String?

    private enum Key {
        static let provider = "auth.provider"
        static let userId = "auth.userId"
        static let displayName = "auth.displayName"
        static let expiresAt = "auth.expiresAt"

        // 이전 버전(게스트·클라이언트 조합 userId) 세션 키. 발견되면 정리한다.
        static let legacyGuestIdentifier = "auth.guestIdentifier"
        static let legacyProviderUserIdentifier = "auth.providerUserIdentifier"
        static let legacyAppleUserIdentifier = "auth.appleUserIdentifier"
    }

    private let defaults: UserDefaults
    private let tokenStore: AccessTokenStore
    private let now: () -> Date

    init(
        defaults: UserDefaults = .standard,
        tokenStore: AccessTokenStore = KeychainTokenStore(),
        now: @escaping () -> Date = Date.init
    ) {
        self.defaults = defaults
        self.tokenStore = tokenStore
        self.now = now
        restore()
    }

    var isSignedIn: Bool {
        userId != nil
    }

    var accountLabel: String {
        guard isSignedIn else {
            return String(localized: "로그인 필요")
        }
        return displayName ?? String(localized: "\(provider?.displayName ?? "") 사용자")
    }

    /// 서버 로그인 응답으로 세션을 갱신한다. 토큰 저장에 실패하면 로그인 상태로 바꾸지 않는다.
    func signIn(provider: LoginProvider, auth: AuthTokenResponse) throws {
        try tokenStore.saveToken(auth.accessToken)

        self.provider = provider
        userId = auth.userId
        displayName = auth.displayName?.isEmpty == false ? auth.displayName : nil
        reloginNotice = nil

        defaults.set(provider.rawValue, forKey: Key.provider)
        defaults.set(auth.userId, forKey: Key.userId)
        if let displayName {
            defaults.set(displayName, forKey: Key.displayName)
        } else {
            defaults.removeObject(forKey: Key.displayName)
        }
        if let expiresIn = auth.expiresIn, expiresIn > 0 {
            defaults.set(now().addingTimeInterval(TimeInterval(expiresIn)), forKey: Key.expiresAt)
        } else {
            defaults.removeObject(forKey: Key.expiresAt)
        }
        statusMessage = String(localized: "\(provider.displayName) 계정으로 로그인했습니다.")
    }

    /// 로그아웃: Keychain 토큰과 저장된 세션 정보를 모두 지운다.
    func signOut(message: String? = nil) {
        clearStoredSession()
        reloginNotice = nil
        statusMessage = message ?? String(localized: "로그아웃했습니다. 다시 로그인하면 동기화를 이어서 할 수 있어요.")
    }

    /// 서버가 401을 반환했을 때 호출한다. 토큰을 버리고 재로그인을 안내한다.
    func handleUnauthorized() {
        clearStoredSession()
        reloginNotice = Self.reloginMessage
        statusMessage = Self.reloginMessage
    }

    func updateStatus(_ message: String) {
        statusMessage = message
    }

    private func restore() {
        // 게스트 식별자는 더 이상 쓰지 않는다.
        let hadGuestSession = defaults.string(forKey: Key.legacyGuestIdentifier) != nil
        defaults.removeObject(forKey: Key.legacyGuestIdentifier)

        let hadLegacySession = defaults.string(forKey: Key.legacyProviderUserIdentifier) != nil
            || defaults.string(forKey: Key.legacyAppleUserIdentifier) != nil
        defaults.removeObject(forKey: Key.legacyProviderUserIdentifier)
        defaults.removeObject(forKey: Key.legacyAppleUserIdentifier)

        let storedProvider = defaults.string(forKey: Key.provider).flatMap(LoginProvider.init(rawValue:))
        let storedUserId = defaults.string(forKey: Key.userId)
        let token = tokenStore.loadToken()
        let expiresAt = defaults.object(forKey: Key.expiresAt) as? Date
        let isExpired = expiresAt.map { $0 <= now() } ?? false

        if let storedProvider, storedProvider.supportsServerToken,
           let storedUserId, token != nil, !isExpired {
            provider = storedProvider
            userId = storedUserId
            displayName = defaults.string(forKey: Key.displayName)
            statusMessage = String(localized: "\(storedProvider.displayName) 계정으로 로그인되어 있습니다.")
            return
        }

        // 불완전·만료·이전 버전 세션은 모두 로그아웃 상태로 정리한다.
        let hadAnySession = storedProvider != nil || storedUserId != nil || token != nil || hadLegacySession
        clearStoredSession()
        if isExpired || hadAnySession {
            reloginNotice = Self.reloginMessage
            statusMessage = Self.reloginMessage
        } else if hadGuestSession {
            statusMessage = String(localized: "게스트 모드가 종료되었어요.") + " " + Self.signInPrompt
        }
    }

    private func clearStoredSession() {
        tokenStore.deleteToken()
        provider = nil
        userId = nil
        displayName = nil
        defaults.removeObject(forKey: Key.provider)
        defaults.removeObject(forKey: Key.userId)
        defaults.removeObject(forKey: Key.displayName)
        defaults.removeObject(forKey: Key.expiresAt)
    }
}
