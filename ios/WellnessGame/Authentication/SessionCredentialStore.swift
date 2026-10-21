import Foundation

/// 로그인·리프레시 응답에서 저장할 토큰 묶음. 기간은 서버가 준 초 단위다.
struct SessionTokens: Equatable, Sendable {
    let accessToken: String
    let expiresIn: Int?
    let refreshToken: String?
    let refreshExpiresIn: Int?
}

/// 서버 세션 자격 증명(액세스·리프레시 토큰과 만료 시각) 저장소.
///
/// - 토큰은 Keychain 두 항목(`accessToken`, `refreshToken`)에 같은 접근성으로 두고, 만료 시각은 UserDefaults에 둔다.
/// - `NetworkClient`와 `UserSession`이 같은 인스턴스를 공유해야 리프레시로 바뀐 토큰이 바로 다음 요청에 실린다.
/// - 리프레시는 `refreshCoordinator`로 한 번에 하나만 보낸다(같은 토큰을 동시에 쓰면 서버가 재사용으로 보고 세션을 폐기한다).
final class SessionCredentialStore: @unchecked Sendable {
    static let refreshTokenAccount = "refreshToken"

    enum Key {
        /// 이전 버전부터 쓰던 액세스 토큰 만료 시각 키.
        static let accessExpiresAt = "auth.expiresAt"
        static let refreshExpiresAt = "auth.refreshExpiresAt"
    }

    let refreshCoordinator = TokenRefreshCoordinator()

    private let accessStore: AccessTokenStore
    private let refreshStore: AccessTokenStore
    private let defaults: UserDefaults
    private let lock = NSLock()

    init(
        accessStore: AccessTokenStore = KeychainTokenStore(),
        refreshStore: AccessTokenStore = KeychainTokenStore(account: SessionCredentialStore.refreshTokenAccount),
        defaults: UserDefaults = .standard
    ) {
        self.accessStore = accessStore
        self.refreshStore = refreshStore
        self.defaults = defaults
    }

    var accessToken: String? {
        lock.withLock { accessStore.loadToken() }
    }

    var refreshToken: String? {
        lock.withLock { refreshStore.loadToken() }
    }

    var accessExpiresAt: Date? {
        lock.withLock { defaults.object(forKey: Key.accessExpiresAt) as? Date }
    }

    var refreshExpiresAt: Date? {
        lock.withLock { defaults.object(forKey: Key.refreshExpiresAt) as? Date }
    }

    /// Keychain을 지금 읽을 수 있는지 확인한다. 첫 잠금 해제 전이면 `KeychainError.locked`를 던진다.
    func checkReadable() throws {
        try lock.withLock {
            try accessStore.checkReadable()
            try refreshStore.checkReadable()
        }
    }

    func isAccessTokenExpired(at now: Date) -> Bool {
        accessExpiresAt.map { $0 <= now } ?? false
    }

    /// 리프레시 토큰이 있고 아직 만료되지 않았는지. 만료 시각을 모르면 서버 판단에 맡긴다.
    func hasUsableRefreshToken(at now: Date) -> Bool {
        lock.withLock {
            guard refreshStore.loadToken() != nil else { return false }
            let expiresAt = defaults.object(forKey: Key.refreshExpiresAt) as? Date
            return expiresAt.map { $0 > now } ?? true
        }
    }

    /// 액세스 토큰이 없거나 `leeway` 안에 만료되면 true. 만료 시각을 모르면 미리 갱신하지 않는다.
    func accessTokenNeedsRefresh(at now: Date, leeway: TimeInterval) -> Bool {
        lock.withLock {
            guard accessStore.loadToken() != nil else { return true }
            guard let expiresAt = defaults.object(forKey: Key.accessExpiresAt) as? Date else { return false }
            return expiresAt.timeIntervalSince(now) <= leeway
        }
    }

    /// 로그인·회전 응답으로 저장값을 바꾼다. 리프레시 토큰을 먼저 저장하고, 실패하면 아무것도 바꾸지 않는다.
    /// 회전 응답 저장이 실패해도 이전 리프레시 토큰은 남으므로, 서버 유예 구간(30초) 안의 다음 요청이
    /// 이전 토큰으로 다시 리프레시해 복구할 수 있다.
    /// 응답에 리프레시 토큰이 없으면(이전 서버) 남아 있던 리프레시 토큰을 지운다.
    func save(_ tokens: SessionTokens, now: Date) throws {
        try lock.withLock {
            if let refreshToken = tokens.refreshToken, !refreshToken.isEmpty {
                try refreshStore.saveToken(refreshToken)
                setExpiry(Key.refreshExpiresAt, seconds: tokens.refreshExpiresIn, now: now)
            } else {
                refreshStore.deleteToken()
                defaults.removeObject(forKey: Key.refreshExpiresAt)
            }
            try accessStore.saveToken(tokens.accessToken)
            setExpiry(Key.accessExpiresAt, seconds: tokens.expiresIn, now: now)
        }
    }

    func clear() {
        lock.withLock {
            accessStore.deleteToken()
            refreshStore.deleteToken()
            defaults.removeObject(forKey: Key.accessExpiresAt)
            defaults.removeObject(forKey: Key.refreshExpiresAt)
        }
    }

    private func setExpiry(_ key: String, seconds: Int?, now: Date) {
        if let seconds, seconds > 0 {
            defaults.set(now.addingTimeInterval(TimeInterval(seconds)), forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

/// 리프레시 요청을 단일 비행(single flight)으로 묶는다.
///
/// 진행 중인 리프레시가 있으면 새로 보내지 않고 그 결과를 함께 기다린다. 거절된 토큰이 이미 다른 리프레시로
/// 교체됐으면(늦게 도착한 401) 다시 보내지 않고 저장된 새 토큰을 돌려준다.
actor TokenRefreshCoordinator {
    private var inflight: Task<String, Error>?

    /// - Parameters:
    ///   - rejectedToken: 서버가 거절했거나 곧 만료될 액세스 토큰.
    ///   - currentToken: 지금 저장된 액세스 토큰을 읽는다.
    ///   - perform: 실제 리프레시를 보내고 새 액세스 토큰을 저장한 뒤 돌려준다.
    func refresh(
        rejecting rejectedToken: String?,
        currentToken: @Sendable () -> String?,
        perform: @escaping @Sendable () async throws -> String
    ) async throws -> String {
        if let inflight {
            return try await inflight.value
        }
        if let latest = currentToken(), latest != rejectedToken {
            return latest
        }
        let task = Task { try await perform() }
        inflight = task
        defer { inflight = nil }
        return try await task.value
    }
}
