import Foundation

/// iPhone에서 받은 로그인 정보를 Watch에 보관하는 저장소. 테스트에서는 메모리 구현으로 대체한다.
protocol WatchCredentialStore: Sendable {
    func load() -> WatchAuthContext?
    func save(_ context: WatchAuthContext) throws
    func delete()
}

extension WatchCredentialStore {
    /// iPhone이 보낸 application context를 반영하고 현재 로그인 정보를 돌려준다.
    /// 빈 값(로그아웃)이거나 이미 만료된 토큰이면 저장된 값을 지우고 nil을 돌려준다.
    /// 저장에 실패해도 이번 실행 동안은 받은 값으로 조회할 수 있게 돌려준다.
    @discardableResult
    func apply(applicationContext: [String: Any], now: Date = Date()) -> WatchAuthContext? {
        guard let context = WatchAuthContext(applicationContext: applicationContext),
              !context.isExpired(at: now) else {
            delete()
            return nil
        }
        try? save(context)
        return context
    }

    /// 저장된 로그인 정보. 만료됐으면 지우고 nil.
    func currentCredentials(now: Date = Date()) -> WatchAuthContext? {
        guard let context = load() else { return nil }
        guard !context.isExpired(at: now) else {
            delete()
            return nil
        }
        return context
    }
}

/// Watch Keychain에 로그인 정보를 JSON으로 보관한다.
/// iPhone과 같은 `KeychainTokenStore`를 써서 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`로 저장한다.
struct KeychainWatchCredentialStore: WatchCredentialStore {
    private let keychain: KeychainTokenStore

    init(keychain: KeychainTokenStore = KeychainTokenStore(account: "watchSession")) {
        self.keychain = keychain
    }

    func load() -> WatchAuthContext? {
        guard let json = keychain.loadToken() else { return nil }
        return try? JSONDecoder().decode(WatchAuthContext.self, from: Data(json.utf8))
    }

    func save(_ context: WatchAuthContext) throws {
        let data = try JSONEncoder().encode(context)
        guard let json = String(data: data, encoding: .utf8) else {
            throw KeychainError.invalidData
        }
        try keychain.saveToken(json)
    }

    func delete() {
        keychain.deleteToken()
    }
}
