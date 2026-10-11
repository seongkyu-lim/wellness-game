import XCTest

// MARK: - Test doubles

/// 요청마다 핸들러로 응답을 정하는 URLProtocol stub. 같은 경로라도 Authorization에 따라 다르게 답할 수 있다.
final class ScriptedURLProtocol: URLProtocol {
    struct Reply {
        let status: Int
        let json: String
        var delay: TimeInterval = 0
    }

    private static let lock = NSLock()
    private static var _handler: (URLRequest) -> Reply = { _ in Reply(status: 404, json: "") }
    private static var _requests: [URLRequest] = []

    static func respond(_ handler: @escaping (URLRequest) -> Reply) {
        lock.withLock {
            _handler = handler
            _requests = []
        }
    }

    static var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    static func requests(path: String) -> [URLRequest] {
        requests.filter { $0.url?.path == path }
    }

    static func json(of request: URLRequest) -> [String: String]? {
        guard let data = StubURLProtocol.bodyData(of: request) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: String]
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let reply = Self.lock.withLock { () -> Reply in
            Self._requests.append(request)
            return Self._handler(request)
        }
        let send = { [weak self] in
            guard let self else { return }
            let response = HTTPURLResponse(
                url: self.request.url!,
                statusCode: reply.status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: Data(reply.json.utf8))
            self.client?.urlProtocolDidFinishLoading(self)
        }
        if reply.delay > 0 {
            DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay, execute: send)
        } else {
            send()
        }
    }

    override func stopLoading() {}
}

/// 테스트에서 시간을 움직이는 시계.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ date: Date) {
        current = date
    }

    var now: Date {
        lock.withLock { current }
    }

    func advance(_ seconds: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(seconds) }
    }
}

private enum AuthV2 {
    static let syncPath = "/api/health-activities/sync"
    static let refreshPath = "/api/auth/refresh"
    static let logoutPath = "/api/auth/logout"
    static let watchTokenPath = "/api/auth/watch-token"

    static let syncResponse = """
    {"userId":"password:alice","date":"2026-10-04","gainedXp":10,"levelUp":false,
     "character":{"level":1,"currentXp":10,"totalXp":10,"nextLevelXp":100,
       "stats":{"str":1,"vit":1,"intStat":1,"discipline":1,"recovery":1}},
     "activityResults":[]}
    """

    static let unauthorized = #"{"status":401,"message":"토큰이 유효하지 않습니다."}"#

    static func login(access: String = "access-1", refresh: String? = "refresh-1") -> AuthTokenResponse {
        AuthTokenResponse(
            userId: "password:alice",
            displayName: "Alice",
            accessToken: access,
            tokenType: "Bearer",
            expiresIn: 3600,
            userIdentifier: "alice",
            refreshToken: refresh,
            refreshExpiresIn: refresh == nil ? nil : 2_592_000
        )
    }

    static func refreshed(access: String, refresh: String) -> String {
        """
        {"userId":"password:alice","accessToken":"\(access)","tokenType":"Bearer","expiresIn":3600,
         "refreshToken":"\(refresh)","refreshExpiresIn":2592000}
        """
    }

    static func watchToken(_ token: String) -> String {
        #"{"accessToken":"\#(token)","tokenType":"Bearer","expiresIn":43200}"#
    }

    static func syncRequest() -> HealthActivitySyncRequest {
        HealthActivitySyncRequest(
            userId: "password:alice",
            date: "2026-10-04",
            activities: [HealthActivityDTO(type: .steps, steps: 1000)]
        )
    }

    static func bearer(_ request: URLRequest) -> String? {
        request.value(forHTTPHeaderField: "Authorization")
    }
}

// MARK: - Refresh

@MainActor
final class AuthRefreshTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var accessStore: InMemoryTokenStore!
    private var refreshStore: InMemoryTokenStore!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!

    override func setUp() {
        super.setUp()
        suiteName = "AuthRefreshTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        accessStore = InMemoryTokenStore()
        refreshStore = InMemoryTokenStore()
        credentials = SessionCredentialStore(accessStore: accessStore, refreshStore: refreshStore, defaults: defaults)
        clock = TestClock(Date(timeIntervalSince1970: 1_900_000_000))
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makeClient() -> NetworkClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ScriptedURLProtocol.self]
        let clock = clock!
        return NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            credentials: credentials,
            now: { clock.now }
        )
    }

    private func makeSession(publisher: WatchAuthPublishing = NoopWatchPublisher()) -> UserSession {
        let clock = clock!
        return UserSession(defaults: defaults, credentials: credentials, watchPublisher: publisher, now: { clock.now })
    }

    /// access-1은 401, access-2는 200. refresh-1 → (access-2, refresh-2)로 회전.
    private func respondWithRotation(refreshStatus: Int = 200, refreshDelay: TimeInterval = 0) {
        ScriptedURLProtocol.respond { request in
            switch request.url?.path {
            case AuthV2.refreshPath:
                guard refreshStatus == 200 else {
                    return .init(status: refreshStatus, json: AuthV2.unauthorized, delay: refreshDelay)
                }
                return .init(status: 200, json: AuthV2.refreshed(access: "access-2", refresh: "refresh-2"), delay: refreshDelay)
            case AuthV2.syncPath, AuthV2.watchTokenPath:
                guard AuthV2.bearer(request) == "Bearer access-2" else {
                    return .init(status: 401, json: AuthV2.unauthorized)
                }
                return request.url?.path == AuthV2.syncPath
                    ? .init(status: 200, json: AuthV2.syncResponse)
                    : .init(status: 200, json: AuthV2.watchToken("watch-1"))
            default:
                return .init(status: 404, json: "")
            }
        }
    }

    func test_401_refreshesOnce_andRetriesOriginalRequest() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation()

        let response = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertEqual(response.gainedXp, 10)
        let refreshes = ScriptedURLProtocol.requests(path: AuthV2.refreshPath)
        XCTAssertEqual(refreshes.count, 1)
        XCTAssertEqual(ScriptedURLProtocol.json(of: refreshes[0]), ["refreshToken": "refresh-1"])
        XCTAssertNil(AuthV2.bearer(refreshes[0]), "리프레시는 공개 경로라 Bearer를 싣지 않는다")
        XCTAssertEqual(
            ScriptedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer),
            ["Bearer access-1", "Bearer access-2"]
        )
    }

    func test_rotatedTokens_replaceStoredValues() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation()
        clock.advance(600)

        _ = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertEqual(accessStore.loadToken(), "access-2")
        XCTAssertEqual(refreshStore.loadToken(), "refresh-2")
        XCTAssertEqual(credentials.accessExpiresAt, clock.now.addingTimeInterval(3600))
        XCTAssertEqual(credentials.refreshExpiresAt, clock.now.addingTimeInterval(2_592_000))
    }

    func test_retryThatStillReturns401_isNotRetriedAgain() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        ScriptedURLProtocol.respond { request in
            request.url?.path == AuthV2.refreshPath
                ? .init(status: 200, json: AuthV2.refreshed(access: "access-2", refresh: "refresh-2"))
                : .init(status: 401, json: AuthV2.unauthorized)
        }

        do {
            _ = try await makeClient().sync(AuthV2.syncRequest())
            XCTFail("재시도 후에도 401이면 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: "토큰이 유효하지 않습니다."))
        }
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.syncPath).count, 2)
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
    }

    func test_concurrent401s_shareSingleRefresh() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation(refreshDelay: 0.2)
        let client = makeClient()

        let results = try await withThrowingTaskGroup(of: Int.self) { group in
            for _ in 0..<5 {
                group.addTask { try await client.sync(AuthV2.syncRequest()).gainedXp }
            }
            return try await group.reduce(into: []) { $0.append($1) }
        }

        XCTAssertEqual(results, Array(repeating: 10, count: 5))
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
        XCTAssertEqual(refreshStore.loadToken(), "refresh-2")
    }

    func test_late401_afterRefreshCompleted_reusesNewTokenWithoutRefreshingAgain() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation()
        let client = makeClient()
        _ = try await client.sync(AuthV2.syncRequest())

        // 이미 회전된 토큰(access-1)으로 늦게 401을 받은 경우를 흉내 낸다.
        let store: SessionCredentialStore = credentials
        let token = try await store.refreshCoordinator.refresh(
            rejecting: "access-1",
            currentToken: { store.accessToken }
        ) {
            XCTFail("이미 갱신됐으면 다시 리프레시하지 않아야 합니다")
            return ""
        }

        XCTAssertEqual(token, "access-2")
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
    }

    func test_tokenAboutToExpire_isRefreshedBeforeRequest() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation()
        clock.advance(3600 - 30)

        _ = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertEqual(ScriptedURLProtocol.requests.map { $0.url?.path }, [AuthV2.refreshPath, AuthV2.syncPath])
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer), ["Bearer access-2"])
    }

    func test_tokenNotNearExpiry_isNotRefreshedEarly() async throws {
        try credentials.save(AuthV2.login(access: "access-2").sessionTokens, now: clock.now)
        respondWithRotation()
        clock.advance(3600 - 120)

        _ = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertTrue(ScriptedURLProtocol.requests(path: AuthV2.refreshPath).isEmpty)
    }

    func test_refreshRejected_throwsUnauthorized_andDashboardSignsOut() async throws {
        let session = makeSession()
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithRotation(refreshStatus: 401)
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(),
            userSession: session
        )

        await viewModel.autoSync(force: true)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertNil(accessStore.loadToken())
        XCTAssertNil(refreshStore.loadToken())
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertEqual(viewModel.statusMessage, UserSession.reloginMessage)
    }

    func test_refreshRejected_surfacesUnauthorizedFromClient() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation(refreshStatus: 401)

        do {
            _ = try await makeClient().sync(AuthV2.syncRequest())
            XCTFail("리프레시가 거절되면 401 에러가 나야 합니다")
        } catch {
            guard case .unauthorized = error as? NetworkError else {
                return XCTFail("unauthorized가 아님: \(error)")
            }
        }
    }

    func test_legacySessionWithoutRefreshToken_worksUntil401_thenSignsOut() async throws {
        let session = makeSession()
        try session.signIn(provider: .password, auth: AuthV2.login(access: "legacy", refresh: nil))
        XCTAssertNil(refreshStore.loadToken())
        var accept = true
        ScriptedURLProtocol.respond { request in
            switch request.url?.path {
            case AuthV2.syncPath where accept:
                return .init(status: 200, json: AuthV2.syncResponse)
            case AuthV2.watchTokenPath where accept:
                return .init(status: 200, json: AuthV2.watchToken("watch-legacy"))
            default:
                return .init(status: 401, json: AuthV2.unauthorized)
            }
        }
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(),
            userSession: session
        )

        await viewModel.autoSync(force: true)
        XCTAssertTrue(session.isSignedIn)
        XCTAssertNotNil(viewModel.syncResponse)
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer), ["Bearer legacy"])

        accept = false
        await viewModel.autoSync(force: true)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertTrue(ScriptedURLProtocol.requests(path: AuthV2.refreshPath).isEmpty, "리프레시 토큰이 없으면 리프레시하지 않는다")
    }

    func test_restore_keepsSession_whenAccessExpiredButRefreshValid() throws {
        try makeSession().signIn(provider: .password, auth: AuthV2.login())
        clock.advance(3600 * 5)

        let relaunched = makeSession()

        XCTAssertTrue(relaunched.isSignedIn)
        XCTAssertNil(relaunched.reloginNotice)
    }

    func test_restore_signsOut_whenRefreshAlsoExpired() throws {
        try makeSession().signIn(provider: .password, auth: AuthV2.login())
        clock.advance(2_592_000 + 1)

        let relaunched = makeSession()

        XCTAssertFalse(relaunched.isSignedIn)
        XCTAssertEqual(relaunched.reloginNotice, UserSession.reloginMessage)
        XCTAssertNil(refreshStore.loadToken())
    }

    // MARK: Logout

    func test_logOut_sendsBearerAndRefreshTokenBody() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        ScriptedURLProtocol.respond { _ in .init(status: 204, json: "") }

        try await makeClient().logOut()

        let request = try XCTUnwrap(ScriptedURLProtocol.requests(path: AuthV2.logoutPath).first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(AuthV2.bearer(request), "Bearer access-1")
        XCTAssertEqual(ScriptedURLProtocol.json(of: request), ["refreshToken": "refresh-1"])
    }

    func test_signOutFlow_clearsLocalSession_evenWhenServerFails() async throws {
        let session = makeSession()
        try session.signIn(provider: .kakao, auth: AuthV2.login())
        ScriptedURLProtocol.respond { _ in .init(status: 500, json: #"{"message":"down"}"#) }
        var signedOutProvider: LoginProvider?

        await SocialSignInFlow(networkClient: makeClient()).signOut(session: session) { signedOutProvider = $0 }

        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.logoutPath).count, 1)
        XCTAssertEqual(signedOutProvider, .kakao)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice)
        XCTAssertNil(accessStore.loadToken())
        XCTAssertNil(refreshStore.loadToken())
    }

    // MARK: Watch token

    private func respondWithWatchTokens(_ tokens: [String?]) {
        var remaining = tokens
        ScriptedURLProtocol.respond { request in
            switch request.url?.path {
            case AuthV2.watchTokenPath:
                let next = remaining.isEmpty ? nil : remaining.removeFirst()
                guard let next else {
                    return .init(status: 500, json: #"{"message":"down"}"#)
                }
                return .init(status: 200, json: AuthV2.watchToken(next))
            case AuthV2.syncPath:
                return .init(status: 200, json: AuthV2.syncResponse)
            default:
                return .init(status: 404, json: "")
            }
        }
    }

    func test_watchToken_isIssuedAndPublished_thenReissuedOnlyNearExpiry() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens(["watch-1", "watch-2"])
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(),
            userSession: session
        )

        await viewModel.autoSync(force: true)

        let first = try XCTUnwrap(spy.published.last ?? nil)
        XCTAssertEqual(first.accessToken, "watch-1")
        XCTAssertEqual(first.userId, "password:alice")
        XCTAssertEqual(first.displayName, "Alice")
        XCTAssertEqual(first.expiresAt, clock.now.addingTimeInterval(43200))
        let issue = try XCTUnwrap(ScriptedURLProtocol.requests(path: AuthV2.watchTokenPath).first)
        XCTAssertEqual(AuthV2.bearer(issue), "Bearer access-1", "watch-token은 전체 권한 토큰으로 발급받는다")

        // 만료까지 1시간 넘게 남았으면 다시 발급하지 않는다(동기화 간격과 무관).
        clock.advance(43200 - 3601)
        await viewModel.autoSync()
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 1)

        // 1시간 이내로 들어오면 재발급해 다시 보낸다.
        clock.advance(2)
        try credentials.save(AuthV2.login(access: "access-1").sessionTokens, now: clock.now)
        await viewModel.autoSync()
        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertEqual((spy.published.last ?? nil)?.accessToken, "watch-2")
    }

    func test_watchTokenFailure_keepsPreviousValue() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens(["watch-1"])
        let client = makeClient()

        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }
        let publishedCount = spy.published.count
        clock.advance(43200 - 60)
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }

        XCTAssertEqual(ScriptedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertEqual(spy.published.count, publishedCount, "실패하면 빈 값이나 새 값을 보내지 않는다")
        XCTAssertEqual((spy.published.last ?? nil)?.accessToken, "watch-1")
        XCTAssertTrue(session.isSignedIn)
    }

    func test_watchToken_isReissuedAfterSignInAgain() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens(["watch-1", "watch-2"])
        let client = makeClient()
        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }

        session.signOut()
        try session.signIn(provider: .password, auth: AuthV2.login())
        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }

        XCTAssertEqual((spy.published.last ?? nil)?.accessToken, "watch-2")
    }
}

// MARK: - Apple nonce / fullName

@MainActor
final class AppleSignInV2Tests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var session: UserSession!
    private var nameBacking: InMemoryTokenStore!
    private var flow: SocialSignInFlow!

    override func setUp() {
        super.setUp()
        suiteName = "AppleSignInV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())
        nameBacking = InMemoryTokenStore()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ScriptedURLProtocol.self]
        flow = SocialSignInFlow(
            networkClient: NetworkClient(
                baseURL: URL(string: "http://stub.local")!,
                session: URLSession(configuration: configuration)
            ),
            pendingAppleNames: PendingAppleNameStore(backing: nameBacking)
        )
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private let success = """
    {"userId":"apple:1","displayName":"홍길동","accessToken":"jwt-apple","tokenType":"Bearer","expiresIn":3600,
     "refreshToken":"refresh-apple","refreshExpiresIn":2592000}
    """

    private func lastAppleBody() throws -> [String: String] {
        let request = try XCTUnwrap(ScriptedURLProtocol.requests(path: "/api/auth/apple/native").last)
        return try XCTUnwrap(ScriptedURLProtocol.json(of: request))
    }

    func test_nonce_isRandomUrlSafe_andHashIsSha256Hex() {
        let first = AppleNonce.make()
        let second = AppleNonce.make()
        XCTAssertEqual(first.count, 43)
        XCTAssertNotEqual(first, second)
        XCTAssertNil(first.rangeOfCharacter(from: CharacterSet(charactersIn: "+/=")))
        XCTAssertEqual(
            AppleNonce.sha256Hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        XCTAssertEqual(AppleNonce.sha256Hex(first).count, 64)
    }

    func test_appleExchange_sendsRawNonce() async throws {
        ScriptedURLProtocol.respond { _ in .init(status: 200, json: self.success) }

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "id-token", fullName: nil, userIdentifier: "u1"),
            nonce: "raw-nonce",
            session: session
        )

        XCTAssertEqual(try lastAppleBody(), ["identityToken": "id-token", "nonce": "raw-nonce"])
        XCTAssertTrue(session.isSignedIn)
    }

    func test_fullName_isKeptAfterFailedExchange_resentNextTime_andRemovedOnSuccess() async throws {
        ScriptedURLProtocol.respond { _ in .init(status: 500, json: #"{"message":"down"}"#) }
        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "t1", fullName: "길동 홍", userIdentifier: "u1"),
            nonce: "n1",
            session: session
        )
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNotNil(nameBacking.loadToken(), "교환 실패 시 이름을 Keychain에 보관한다")

        // Apple은 두 번째 인증부터 이름을 주지 않는다.
        ScriptedURLProtocol.respond { _ in .init(status: 200, json: self.success) }
        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "t2", fullName: nil, userIdentifier: "u1"),
            nonce: "n2",
            session: session
        )

        XCTAssertEqual(try lastAppleBody(), ["identityToken": "t2", "fullName": "길동 홍", "nonce": "n2"])
        XCTAssertTrue(session.isSignedIn)
        XCTAssertNil(nameBacking.loadToken(), "성공하면 보관한 이름을 지운다")
    }

    func test_pendingFullName_isNotSentForDifferentAppleUser() async throws {
        PendingAppleNameStore(backing: nameBacking).save("길동 홍", for: "u1")
        ScriptedURLProtocol.respond { _ in .init(status: 200, json: self.success) }

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "t", fullName: nil, userIdentifier: "u2"),
            nonce: nil,
            session: session
        )

        XCTAssertEqual(try lastAppleBody(), ["identityToken": "t"])
        XCTAssertNotNil(nameBacking.loadToken(), "다른 사용자의 보관 이름은 지우지 않는다")
    }

    func test_signInStoresRefreshToken() async throws {
        let refreshStore = InMemoryTokenStore()
        let credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: refreshStore, defaults: defaults)
        let session = UserSession(defaults: defaults, credentials: credentials, watchPublisher: NoopWatchPublisher())
        ScriptedURLProtocol.respond { _ in .init(status: 200, json: self.success) }

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "t", fullName: nil),
            nonce: nil,
            session: session
        )

        XCTAssertEqual(refreshStore.loadToken(), "refresh-apple")
        XCTAssertEqual(credentials.accessToken, "jwt-apple")
        XCTAssertNotNil(credentials.refreshExpiresAt)
    }
}
