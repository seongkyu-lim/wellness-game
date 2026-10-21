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
}

/// 서버가 발급한 토큰과 사용자 ID로 로그인 상태를 관리한다.
///
/// - 토큰은 `SessionCredentialStore`(Keychain), 표시용 정보는 UserDefaults에 둔다.
/// - `userId`는 서버 응답 값만 사용하며 앱이 직접 조합하지 않는다.
/// - 게스트 세션은 없다. 로그인하지 않으면 `userId == nil`이다.
/// - 액세스 토큰이 만료돼도 리프레시 토큰이 유효하면 로그인 상태를 유지한다(다음 요청에서 갱신).
/// - Watch에는 iPhone 토큰 대신 `/api/auth/watch-token`으로 받은 조회 전용 토큰을 보낸다
///   (`refreshWatchTokenIfNeeded`). 로그아웃·401이면 빈 값을 보낸다.
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

        // 이전 버전(게스트·클라이언트 조합 userId) 세션 키. 발견되면 정리한다.
        static let legacyGuestIdentifier = "auth.guestIdentifier"
        static let legacyProviderUserIdentifier = "auth.providerUserIdentifier"
        static let legacyAppleUserIdentifier = "auth.appleUserIdentifier"
    }

    /// Watch 토큰(기본 7일)이 이 시간 안에 만료되면 다시 발급한다.
    static let watchTokenRenewalLeadTime: TimeInterval = 86_400

    private let defaults: UserDefaults
    private let credentials: SessionCredentialStore
    private let now: () -> Date
    private let watchPublisher: WatchAuthPublishing
    /// 마지막으로 Watch에 보낸 조회 토큰. 앱을 다시 실행하면 비어 있어 첫 동기화 때 다시 발급한다.
    private var watchToken: WatchAuthContext?
    private var isIssuingWatchToken = false
    /// 로그인 직후 첫 발급을 아직 시도하지 않았으면 true. 그 발급이 실패하면 한 번만 지연 재시도한다.
    private var isAwaitingFirstWatchToken = false
    /// 로그인 직후 발급 실패 시 재시도까지 기다리는 시간.
    private let watchTokenRetryDelay: TimeInterval
    /// 예약된 지연 재시도. 로그아웃하면 취소한다.
    private(set) var watchTokenRetryTask: Task<Void, Never>?

    /// - Parameter credentials: `NetworkClient`와 같은 인스턴스를 넘겨야 한다. nil이면 `defaults`를 쓰는 Keychain 저장소.
    init(
        defaults: UserDefaults = .standard,
        credentials: SessionCredentialStore? = nil,
        watchPublisher: WatchAuthPublishing = WatchAuthRelay.shared,
        watchTokenRetryDelay: TimeInterval = 30,
        now: @escaping () -> Date = Date.init
    ) {
        self.watchTokenRetryDelay = watchTokenRetryDelay
        self.defaults = defaults
        self.credentials = credentials ?? SessionCredentialStore(defaults: defaults)
        self.watchPublisher = watchPublisher
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
        try credentials.save(auth.sessionTokens, now: now())

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
        // 새 세션이므로 Watch 토큰도 다음 동기화 때 새로 발급한다.
        watchToken = nil
        watchTokenRetryTask?.cancel()
        watchTokenRetryTask = nil
        isAwaitingFirstWatchToken = true
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

    /// Watch 조회 토큰이 없거나 만료 1일 전이면 다시 발급받아 Watch에 보낸다.
    /// 앱 포그라운드·백그라운드 동기화 때 호출한다. 발급이 실패하면 이전 값을 유지하고 다음 호출에서 다시 시도한다.
    /// 로그인 직후 첫 발급이 실패하면 `watchTokenRetryDelay` 뒤 한 번만 다시 시도한다.
    /// 리프레시까지 거절(401)되면 세션이 끝난 것이므로 재로그인을 안내한다.
    func refreshWatchTokenIfNeeded(issue: @escaping () async throws -> WatchTokenResponse) async {
        guard let userId, !isIssuingWatchToken else { return }
        if let watchToken, watchToken.userId == userId {
            guard let expiresAt = watchToken.expiresAt,
                  expiresAt.timeIntervalSince(now()) <= Self.watchTokenRenewalLeadTime else {
                return
            }
        }
        let isFirstAfterSignIn = isAwaitingFirstWatchToken
        isAwaitingFirstWatchToken = false
        isIssuingWatchToken = true
        defer { isIssuingWatchToken = false }
        do {
            let response = try await issue()
            // 발급 중 로그아웃·계정 전환이 일어났으면 버린다.
            guard self.userId == userId else { return }
            let context = WatchAuthContext(
                accessToken: response.accessToken,
                userId: userId,
                displayName: displayName,
                expiresAt: response.expiresIn > 0 ? now().addingTimeInterval(TimeInterval(response.expiresIn)) : nil,
                language: AppLanguage.acceptLanguage()
            )
            watchToken = context
            watchPublisher.publish(context)
        } catch NetworkError.unauthorized {
            guard self.userId == userId else { return }
            handleUnauthorized()
        } catch {
            // 일시적 실패: Watch는 이전 토큰을 계속 쓴다.
            guard isFirstAfterSignIn, self.userId == userId else { return }
            scheduleWatchTokenRetry(issue: issue)
        }
    }

    private func scheduleWatchTokenRetry(issue: @escaping () async throws -> WatchTokenResponse) {
        watchTokenRetryTask?.cancel()
        let delay = watchTokenRetryDelay
        watchTokenRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(delay, 0) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.refreshWatchTokenIfNeeded(issue: issue)
        }
    }

    private func restore() {
        // 첫 잠금 해제 전 백그라운드 실행이면 Keychain을 읽을 수 없다. 토큰이 없다고 보고 세션을 지우면
        // 로그인이 풀리므로, 이번 실행에서는 로그아웃 상태로만 두고 저장값은 건드리지 않는다.
        guard (try? credentials.checkReadable()) != nil else { return }

        // 게스트 식별자는 더 이상 쓰지 않는다.
        let hadGuestSession = defaults.string(forKey: Key.legacyGuestIdentifier) != nil
        defaults.removeObject(forKey: Key.legacyGuestIdentifier)

        let hadLegacySession = defaults.string(forKey: Key.legacyProviderUserIdentifier) != nil
            || defaults.string(forKey: Key.legacyAppleUserIdentifier) != nil
        defaults.removeObject(forKey: Key.legacyProviderUserIdentifier)
        defaults.removeObject(forKey: Key.legacyAppleUserIdentifier)

        let storedProvider = defaults.string(forKey: Key.provider).flatMap(LoginProvider.init(rawValue:))
        let storedUserId = defaults.string(forKey: Key.userId)
        let token = credentials.accessToken
        let date = now()
        let canRefresh = credentials.hasUsableRefreshToken(at: date)
        let accessExpired = credentials.isAccessTokenExpired(at: date)
        let isExpired = accessExpired && !canRefresh
        // 액세스 토큰이 만료됐거나 없어도 리프레시 토큰이 유효하면 첫 요청에서 갱신한다.
        let hasCredentials = (token != nil && !accessExpired) || canRefresh

        if let storedProvider, let storedUserId, hasCredentials {
            provider = storedProvider
            userId = storedUserId
            displayName = defaults.string(forKey: Key.displayName)
            statusMessage = String(localized: "\(storedProvider.displayName) 계정으로 로그인되어 있습니다.")
            return
        }

        // 불완전·만료·이전 버전 세션은 모두 로그아웃 상태로 정리한다.
        let hadAnySession = storedProvider != nil || storedUserId != nil || token != nil
            || credentials.refreshToken != nil || hadLegacySession
        clearStoredSession()
        if isExpired || hadAnySession {
            reloginNotice = Self.reloginMessage
            statusMessage = Self.reloginMessage
        } else if hadGuestSession {
            statusMessage = String(localized: "게스트 모드가 종료되었어요.") + " " + Self.signInPrompt
        }
    }

    private func clearStoredSession() {
        credentials.clear()
        provider = nil
        userId = nil
        displayName = nil
        watchToken = nil
        isAwaitingFirstWatchToken = false
        watchTokenRetryTask?.cancel()
        watchTokenRetryTask = nil
        defaults.removeObject(forKey: Key.provider)
        defaults.removeObject(forKey: Key.userId)
        defaults.removeObject(forKey: Key.displayName)
        watchPublisher.publish(nil)
    }
}
