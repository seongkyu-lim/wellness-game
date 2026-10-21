import Security
import XCTest

// MARK: - SessionCredentialStore

final class SessionCredentialStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var accessStore: InMemoryTokenStore!
    private let now = Date(timeIntervalSince1970: 1_792_540_800)

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        accessStore = InMemoryTokenStore()
    }

    private func makeStore(refreshStore: AccessTokenStore = InMemoryTokenStore()) -> SessionCredentialStore {
        SessionCredentialStore(accessStore: accessStore, refreshStore: refreshStore, defaults: defaults)
    }

    func test_저장하면_만료시각을_초단위로_기록하고_기간이_0이하면_만료시각을_지운다() throws {
        let store = makeStore()

        try store.save(SessionTokens(accessToken: "a", expiresIn: 3600, refreshToken: "r", refreshExpiresIn: 0), now: now)

        XCTAssertEqual(store.accessToken, "a")
        XCTAssertEqual(store.refreshToken, "r")
        XCTAssertEqual(store.accessExpiresAt, now.addingTimeInterval(3600))
        XCTAssertNil(store.refreshExpiresAt)
        // 리프레시 만료 시각을 모르면 서버 판단에 맡기므로 쓸 수 있다고 본다.
        XCTAssertTrue(store.hasUsableRefreshToken(at: now.addingTimeInterval(86_400 * 365)))
    }

    func test_리프레시토큰이_없는_응답은_남아있던_리프레시토큰과_만료시각을_지운다() throws {
        let store = makeStore()
        try store.save(SessionTokens(accessToken: "a", expiresIn: 3600, refreshToken: "r", refreshExpiresIn: 7200), now: now)

        try store.save(SessionTokens(accessToken: "b", expiresIn: 3600, refreshToken: "", refreshExpiresIn: nil), now: now)

        XCTAssertEqual(store.accessToken, "b")
        XCTAssertNil(store.refreshToken)
        XCTAssertNil(store.refreshExpiresAt)
        XCTAssertFalse(store.hasUsableRefreshToken(at: now))
    }

    func test_리프레시토큰_저장이_실패하면_아무것도_바꾸지_않는다() throws {
        let refreshStore = FlakyTokenStore()
        let store = makeStore(refreshStore: refreshStore)
        try store.save(SessionTokens(accessToken: "a-1", expiresIn: 3600, refreshToken: "r-1", refreshExpiresIn: 7200), now: now)
        refreshStore.failNextSave = true

        XCTAssertThrowsError(try store.save(
            SessionTokens(accessToken: "a-2", expiresIn: 60, refreshToken: "r-2", refreshExpiresIn: 60),
            now: now.addingTimeInterval(10)
        ))

        XCTAssertEqual(store.accessToken, "a-1")
        XCTAssertEqual(store.refreshToken, "r-1")
        XCTAssertEqual(store.accessExpiresAt, now.addingTimeInterval(3600))
        XCTAssertEqual(store.refreshExpiresAt, now.addingTimeInterval(7200))
    }

    func test_사전갱신_판단은_토큰없음이면_필요_만료시각_모르면_불필요_leeway_안이면_필요() throws {
        let store = makeStore()
        XCTAssertTrue(store.accessTokenNeedsRefresh(at: now, leeway: 60), "토큰이 없으면 갱신이 필요하다")

        try store.save(SessionTokens(accessToken: "a", expiresIn: nil, refreshToken: "r", refreshExpiresIn: nil), now: now)
        XCTAssertFalse(store.accessTokenNeedsRefresh(at: now.addingTimeInterval(86_400), leeway: 60))
        XCTAssertFalse(store.isAccessTokenExpired(at: now.addingTimeInterval(86_400)))

        try store.save(SessionTokens(accessToken: "a", expiresIn: 600, refreshToken: "r", refreshExpiresIn: nil), now: now)
        XCTAssertFalse(store.accessTokenNeedsRefresh(at: now.addingTimeInterval(539), leeway: 60))
        XCTAssertTrue(store.accessTokenNeedsRefresh(at: now.addingTimeInterval(540), leeway: 60), "경계값은 갱신한다")
        XCTAssertFalse(store.isAccessTokenExpired(at: now.addingTimeInterval(599)))
        XCTAssertTrue(store.isAccessTokenExpired(at: now.addingTimeInterval(600)))
    }

    func test_리프레시토큰은_만료시각이_지나면_쓸수없다() throws {
        let store = makeStore()
        try store.save(SessionTokens(accessToken: "a", expiresIn: 60, refreshToken: "r", refreshExpiresIn: 7200), now: now)

        XCTAssertTrue(store.hasUsableRefreshToken(at: now.addingTimeInterval(7199)))
        XCTAssertFalse(store.hasUsableRefreshToken(at: now.addingTimeInterval(7200)))
    }

    func test_잠긴_저장소가_하나라도_있으면_읽기확인이_locked를_던진다() {
        let refreshStore = LockableTokenStore(token: "r")
        let store = makeStore(refreshStore: refreshStore)
        XCTAssertNoThrow(try store.checkReadable())

        refreshStore.isLocked = true

        XCTAssertThrowsError(try store.checkReadable()) { error in
            XCTAssertEqual(error as? KeychainError, .locked)
        }
    }

    func test_정리하면_토큰과_만료시각을_모두_지운다() throws {
        let store = makeStore()
        try store.save(SessionTokens(accessToken: "a", expiresIn: 60, refreshToken: "r", refreshExpiresIn: 120), now: now)

        store.clear()

        XCTAssertNil(store.accessToken)
        XCTAssertNil(store.refreshToken)
        XCTAssertNil(store.accessExpiresAt)
        XCTAssertNil(store.refreshExpiresAt)
        XCTAssertNil(defaults.object(forKey: SessionCredentialStore.Key.accessExpiresAt))
    }
}

// MARK: - TokenRefreshCoordinator

/// 리프레시 동작을 열어 줄 때까지 붙잡아 두는 가짜 서버.
private final class GatedRefresh: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var _performCount = 0
    private var _stored: String?
    private var _isOpen = false
    var failure: Error?
    /// 리프레시가 시작되면(게이트에서 기다리기 시작하면) 채운다.
    var performStarted: XCTestExpectation?

    init(stored: String?) {
        _stored = stored
    }

    var performCount: Int { lock.withLock { _performCount } }
    var stored: String? { lock.withLock { _stored } }

    func perform() async throws -> String {
        lock.withLock { _performCount += 1 }
        performStarted?.fulfill()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow = lock.withLock { () -> Bool in
                if _isOpen { return true }
                self.continuation = continuation
                return false
            }
            if resumeNow { continuation.resume() }
        }
        if let failure { throw failure }
        lock.withLock { _stored = "new-\(_performCount)" }
        return stored ?? ""
    }

    func open() {
        let pending = lock.withLock { () -> CheckedContinuation<Void, Never>? in
            _isOpen = true
            defer { continuation = nil }
            return continuation
        }
        pending?.resume()
    }
}

final class TokenRefreshCoordinatorTests: XCTestCase {
    func test_동시에_요청해도_리프레시는_한번만_나가고_모두_진행중인_리프레시에_합류한다() async throws {
        let coordinator = TokenRefreshCoordinator()
        let server = GatedRefresh(stored: "old")
        let started = expectation(description: "첫 리프레시 시작")
        server.performStarted = started
        // 합류하지 않고 저장된 토큰을 따로 확인한 횟수. 합류 경로면 0이어야 한다.
        let separateChecks = CompletionCounter()

        async let first = coordinator.refresh(rejecting: "old", currentToken: { server.stored }, perform: server.perform)
        await fulfillment(of: [started], timeout: 5)

        let calling = expectation(description: "합류 요청 시작")
        calling.expectedFulfillmentCount = 2
        let follower: @Sendable () async throws -> String = {
            calling.fulfill()
            return try await coordinator.refresh(
                rejecting: "old",
                currentToken: {
                    separateChecks()
                    return server.stored
                },
                perform: server.perform
            )
        }
        async let second = follower()
        async let third = follower()
        await fulfillment(of: [calling], timeout: 5)
        XCTAssertTrue(coordinator.isRefreshing, "리프레시가 나가 있는 동안 동기적으로 알 수 있어야 한다")
        // 합류 요청이 액터에 들어갈 몇 번의 양보 뒤에 리프레시를 끝낸다. 늦게 들어오면 아래 separateChecks 단언이 실패한다.
        for _ in 0..<20 {
            await Task.yield()
        }
        server.open()

        let results = try await [first, second, third]

        XCTAssertEqual(results, ["new-1", "new-1", "new-1"])
        XCTAssertEqual(server.performCount, 1)
        XCTAssertEqual(separateChecks.count, 0, "합류한 요청은 진행 중인 리프레시 결과를 받아야 한다")
        XCTAssertFalse(coordinator.isRefreshing)
    }

    func test_거절된_토큰이_이미_교체됐으면_리프레시없이_현재토큰을_돌려준다() async throws {
        let coordinator = TokenRefreshCoordinator()
        let server = GatedRefresh(stored: "rotated")
        server.open()

        let token = try await coordinator.refresh(rejecting: "old", currentToken: { server.stored }, perform: server.perform)

        XCTAssertEqual(token, "rotated")
        XCTAssertEqual(server.performCount, 0)
    }

    func test_리프레시_실패는_호출자에게_전달되고_다음_호출은_다시_시도한다() async throws {
        let coordinator = TokenRefreshCoordinator()
        let server = GatedRefresh(stored: "old")
        server.failure = NetworkError.unauthorized(message: "")
        server.open()

        do {
            _ = try await coordinator.refresh(rejecting: "old", currentToken: { server.stored }, perform: server.perform)
            XCTFail("실패가 전달되어야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: ""))
        }
        XCTAssertFalse(coordinator.isRefreshing, "실패해도 진행 표시는 내려가야 한다")

        server.failure = nil
        let token = try await coordinator.refresh(rejecting: "old", currentToken: { server.stored }, perform: server.perform)
        XCTAssertEqual(token, "new-2")
        XCTAssertEqual(server.performCount, 2)
    }

    func test_저장된_토큰이_없으면_리프레시를_보낸다() async throws {
        let coordinator = TokenRefreshCoordinator()
        let server = GatedRefresh(stored: nil)
        server.open()

        let token = try await coordinator.refresh(rejecting: nil, currentToken: { nil }, perform: server.perform)

        XCTAssertEqual(token, "new-1")
    }
}

// MARK: - KeychainTokenStore (시뮬레이터 실제 Keychain)

final class KeychainTokenStoreTests: XCTestCase {
    private var store: KeychainTokenStore!

    override func setUpWithError() throws {
        store = KeychainTokenStore(service: "WellnessGameTests.\(UUID().uuidString)", account: "accessToken")
        // 호스트 앱 없이 도는 테스트 번들은 Keychain 권한이 없을 수 있다(errSecMissingEntitlement).
        do {
            try store.saveToken("probe")
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("이 실행 환경에서는 Keychain을 쓸 수 없습니다(\(status)).")
        }
        store.deleteToken()
    }

    override func tearDown() {
        store?.deleteToken()
        super.tearDown()
    }

    func test_저장한_토큰을_읽고_다시_저장하면_덮어쓰고_지우면_없다() throws {
        XCTAssertNil(store.loadToken())

        try store.saveToken("token-1")
        XCTAssertEqual(store.loadToken(), "token-1")

        try store.saveToken("token-2")
        XCTAssertEqual(store.loadToken(), "token-2")

        store.deleteToken()
        XCTAssertNil(store.loadToken())
        store.deleteToken() // 없는 항목을 지워도 문제없다.
    }

    func test_빈_토큰은_저장하지_않는다() {
        XCTAssertThrowsError(try store.saveToken("")) { error in
            XCTAssertEqual(error as? KeychainError, .invalidData)
        }
        XCTAssertNil(store.loadToken())
    }

    func test_같은_서비스라도_계정이_다르면_서로_영향이_없다() throws {
        let refresh = KeychainTokenStore(service: store.service, account: SessionCredentialStore.refreshTokenAccount)
        defer { refresh.deleteToken() }

        try store.saveToken("access")
        try refresh.saveToken("refresh")
        store.deleteToken()

        XCTAssertNil(store.loadToken())
        XCTAssertEqual(refresh.loadToken(), "refresh")
    }

    func test_항목이_없어도_잠금이_아니면_읽을수_있는_상태다() {
        XCTAssertNoThrow(try store.checkReadable())
    }

    func test_실제_Keychain으로도_세션저장소가_토큰을_보관한다() throws {
        let defaults = ScratchDefaults.make(for: self)
        let refresh = KeychainTokenStore(service: store.service, account: SessionCredentialStore.refreshTokenAccount)
        let credentials = SessionCredentialStore(accessStore: store, refreshStore: refresh, defaults: defaults)

        try credentials.save(SessionTokens(accessToken: "a", expiresIn: 60, refreshToken: "r", refreshExpiresIn: 120), now: Date())
        XCTAssertEqual(credentials.accessToken, "a")
        XCTAssertEqual(credentials.refreshToken, "r")

        credentials.clear()
        XCTAssertNil(store.loadToken())
        XCTAssertNil(refresh.loadToken())
    }
}

final class KeychainErrorTests: XCTestCase {
    func test_Keychain_오류문구는_상태코드를_포함한다() {
        let message = KeychainError.unexpectedStatus(-25_299).errorDescription ?? ""
        // 지역 설정에 따라 "-25,299"처럼 자릿수 구분이 붙을 수 있으므로 숫자만 비교한다.
        XCTAssertTrue(message.contains("Keychain"), message)
        XCTAssertTrue(message.filter(\.isNumber).contains("25299"), message)
    }
}
