import XCTest

// MARK: - Test doubles

/// 네트워크 없이 요청을 가로채 응답을 돌려주는 URLProtocol stub.
/// 기본 응답 하나(`setStub`) 또는 경로별 응답 맵(`setStubs`)을 쓴다. 맵에 없는 경로는 기본 응답을 돌려준다.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        let status: Int
        let body: Data

        init(status: Int, json: String) {
            self.status = status
            body = Data(json.utf8)
        }
    }

    private static let lock = NSLock()
    private static var _defaultStub = Stub(status: 200, json: "")
    private static var _stubsByPath: [String: Stub] = [:]
    private static var _requests: [URLRequest] = []

    static func setStub(status: Int, json: String) {
        setStubs([:], default: Stub(status: status, json: json))
    }

    /// 경로(`/api/...`)별 응답을 정한다. 맵에 없는 경로는 `default`(기본 404)를 돌려준다.
    static func setStubs(_ stubs: [String: Stub], default defaultStub: Stub = Stub(status: 404, json: "")) {
        lock.withLock {
            _defaultStub = defaultStub
            _stubsByPath = stubs
            _requests = []
        }
    }

    static var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    static func request(forPath path: String) -> URLRequest? {
        requests.first { $0.url?.path == path }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let stub = Self.lock.withLock { () -> Stub in
            Self._requests.append(request)
            return Self._stubsByPath[request.url?.path ?? ""] ?? Self._defaultStub
        }
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stub.status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// URLProtocol에서는 httpBody가 스트림으로 바뀌므로 스트림까지 읽는다.
    static func bodyData(of request: URLRequest) -> Data? {
        if let body = request.httpBody {
            return body
        }
        guard let stream = request.httpBodyStream else {
            return nil
        }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

final class InMemoryTokenStore: AccessTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?

    init(token: String? = nil) {
        self.token = token
    }

    func loadToken() -> String? {
        lock.withLock { token }
    }

    func saveToken(_ token: String) throws {
        lock.withLock { self.token = token }
    }

    func deleteToken() {
        lock.withLock { token = nil }
    }
}

// MARK: - Fixtures

private enum Fixture {
    static let syncResponse = """
    {"userId":"google:123","date":"2026-10-04","gainedXp":10,"levelUp":false,
     "character":{"level":1,"currentXp":10,"totalXp":10,"nextLevelXp":100,
       "stats":{"str":1,"vit":1,"intStat":1,"discipline":1,"recovery":1}},
     "activityResults":[]}
    """

    static let passwordLogin = """
    {"userIdentifier":"alice","userId":"password:alice","displayName":"Alice",
     "accessToken":"jwt-password","tokenType":"Bearer","expiresIn":2592000}
    """

    static let googleLogin = """
    {"userId":"google:123","displayName":"Google User",
     "accessToken":"jwt-google","tokenType":"Bearer","expiresIn":2592000}
    """

    static let unauthorized = """
    {"timestamp":"2026-10-04T00:00:00Z","status":401,"error":"Unauthorized","message":"토큰이 유효하지 않습니다."}
    """

    static let forbidden = """
    {"timestamp":"2026-10-04T00:00:00Z","status":403,"error":"Forbidden","message":"다른 사용자의 데이터입니다."}
    """

    static func syncRequest(userId: String = "google:123") -> HealthActivitySyncRequest {
        HealthActivitySyncRequest(
            userId: userId,
            date: "2026-10-04",
            activities: [HealthActivityDTO(type: .steps, steps: 1000)]
        )
    }
}

// MARK: - NetworkClient

final class NetworkClientAuthTests: XCTestCase {
    private func makeClient(tokenStore: AccessTokenStore?) -> NetworkClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            tokenStore: tokenStore
        )
    }

    func test_sync_attachesBearerToken_whenTokenStored() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.syncResponse)
        let client = makeClient(tokenStore: InMemoryTokenStore(token: "abc.def"))

        _ = try await client.sync(Fixture.syncRequest())

        let request = try XCTUnwrap(StubURLProtocol.requests.last)
        XCTAssertEqual(request.url?.path, "/api/health-activities/sync")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer abc.def")
    }

    func test_requests_sendAcceptLanguage() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.passwordLogin)
        _ = try await makeClient(tokenStore: nil).logIn(LoginRequest(username: "alice", password: "password1"))
        let header = StubURLProtocol.requests.last?.value(forHTTPHeaderField: "Accept-Language")
        XCTAssertEqual(header, AppLanguage.acceptLanguage())
        XCTAssertTrue(["ko", "en"].contains(header ?? ""))
    }

    func test_sync_omitsAuthorization_whenNoToken() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.syncResponse)
        let client = makeClient(tokenStore: InMemoryTokenStore())

        _ = try await client.sync(Fixture.syncRequest())

        let request = try XCTUnwrap(StubURLProtocol.requests.last)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    func test_sync_maps401ToUnauthorized() async {
        StubURLProtocol.setStub(status: 401, json: Fixture.unauthorized)
        let client = makeClient(tokenStore: InMemoryTokenStore(token: "expired"))

        do {
            _ = try await client.sync(Fixture.syncRequest())
            XCTFail("401은 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: "토큰이 유효하지 않습니다."))
        }
    }

    func test_sync_maps403ToServerError_notUnauthorized() async {
        StubURLProtocol.setStub(status: 403, json: Fixture.forbidden)
        let client = makeClient(tokenStore: InMemoryTokenStore(token: "abc"))

        do {
            _ = try await client.sync(Fixture.syncRequest())
            XCTFail("403은 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .server(status: 403, message: "다른 사용자의 데이터입니다."))
        }
    }

    func test_login_doesNotSendStaleToken() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.passwordLogin)
        let client = makeClient(tokenStore: InMemoryTokenStore(token: "stale"))

        _ = try await client.logIn(LoginRequest(username: "alice", password: "password1"))

        let request = try XCTUnwrap(StubURLProtocol.requests.last)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    func test_googleNative_postsIdToken_andDecodesTokenResponse() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.googleLogin)
        let client = makeClient(tokenStore: InMemoryTokenStore())

        let response = try await client.signInWithGoogle(idToken: "google-id-token")

        let request = try XCTUnwrap(StubURLProtocol.requests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/auth/google/native")
        let body = try XCTUnwrap(StubURLProtocol.bodyData(of: request))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["idToken"] as? String, "google-id-token")
        XCTAssertEqual(response.userId, "google:123")
        XCTAssertEqual(response.accessToken, "jwt-google")
        XCTAssertEqual(response.expiresIn, 2_592_000)
    }
}

// MARK: - Session / login integration

@MainActor
final class UserSessionAuthTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "UserSessionAuthTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func makeClient(tokenStore: AccessTokenStore) -> NetworkClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            tokenStore: tokenStore
        )
    }

    func test_passwordLogin_storesServerToken_andServerUserId() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.passwordLogin)
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        let service = CredentialAuthService(networkClient: makeClient(tokenStore: store))

        await service.logIn(username: "alice", password: "password1", into: session)

        XCTAssertNil(service.errorMessage)
        XCTAssertEqual(store.loadToken(), "jwt-password")
        XCTAssertEqual(session.userId, "password:alice")
        XCTAssertEqual(session.provider, .password)
        XCTAssertTrue(session.isSignedIn)
    }

    func test_tokenFromLogin_isSentOnNextSync() async throws {
        StubURLProtocol.setStub(status: 200, json: Fixture.passwordLogin)
        let store = InMemoryTokenStore()
        let client = makeClient(tokenStore: store)
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        await CredentialAuthService(networkClient: client)
            .logIn(username: "alice", password: "password1", into: session)

        StubURLProtocol.setStub(status: 200, json: Fixture.syncResponse)
        _ = try await client.sync(Fixture.syncRequest(userId: try XCTUnwrap(session.userId)))

        XCTAssertEqual(
            StubURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"),
            "Bearer jwt-password"
        )
    }

    func test_failedLogin_keepsSignedOut_andStoresNoToken() async {
        StubURLProtocol.setStub(status: 401, json: Fixture.unauthorized)
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        let service = CredentialAuthService(networkClient: makeClient(tokenStore: store))

        await service.logIn(username: "alice", password: "wrongpass", into: session)

        XCTAssertNotNil(service.errorMessage)
        XCTAssertNil(store.loadToken())
        XCTAssertFalse(session.isSignedIn)
    }

    func test_restore_keepsSessionWithTokenAcrossLaunches() throws {
        let store = InMemoryTokenStore()
        let first = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        try first.signIn(provider: .google, auth: decodeAuth(Fixture.googleLogin))

        let relaunched = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())

        XCTAssertEqual(relaunched.userId, "google:123")
        XCTAssertEqual(relaunched.provider, .google)
        XCTAssertNil(relaunched.reloginNotice)
    }

    func test_restore_signsOut_whenTokenMissing() throws {
        let store = InMemoryTokenStore()
        try UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
            .signIn(provider: .google, auth: decodeAuth(Fixture.googleLogin))
        store.deleteToken()

        let relaunched = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())

        XCTAssertFalse(relaunched.isSignedIn)
        XCTAssertEqual(relaunched.reloginNotice, UserSession.reloginMessage)
    }

    func test_restore_signsOut_whenTokenExpired() throws {
        let store = InMemoryTokenStore()
        let issuedAt = Date(timeIntervalSince1970: 1_800_000_000)
        try UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher(), now: { issuedAt })
            .signIn(provider: .password, auth: decodeAuth(Fixture.passwordLogin))

        let later = issuedAt.addingTimeInterval(2_592_001)
        let relaunched = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher(), now: { later })

        XCTAssertFalse(relaunched.isSignedIn)
        XCTAssertNil(store.loadToken())
        XCTAssertEqual(relaunched.reloginNotice, UserSession.reloginMessage)
    }

    func test_existingGuestSession_isMigratedToSignedOut() {
        defaults.set("old-guest-uuid", forKey: "auth.guestIdentifier")

        let session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.userId)
        XCTAssertNil(defaults.string(forKey: "auth.guestIdentifier"))
    }

    func test_legacyClientComposedSession_isMigratedToSignedOut() {
        defaults.set("google", forKey: "auth.provider")
        defaults.set("123", forKey: "auth.providerUserIdentifier")

        let session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(defaults.string(forKey: "auth.provider"))
        XCTAssertNil(defaults.string(forKey: "auth.providerUserIdentifier"))
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
    }

    func test_signOut_clearsKeychainToken() throws {
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        try session.signIn(provider: .google, auth: decodeAuth(Fixture.googleLogin))

        session.signOut()

        XCTAssertNil(store.loadToken())
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice)
    }

    func test_handleUnauthorized_clearsToken_andShowsReloginNotice() throws {
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        try session.signIn(provider: .password, auth: decodeAuth(Fixture.passwordLogin))

        session.handleUnauthorized()

        XCTAssertNil(store.loadToken())
        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertFalse(UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher()).isSignedIn)
    }

    func test_dashboardSync_on401_signsOutSession() async throws {
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        try session.signIn(provider: .password, auth: decodeAuth(Fixture.passwordLogin))
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(tokenStore: store),
            userSession: session
        )
        StubURLProtocol.setStub(status: 401, json: Fixture.unauthorized)

        await viewModel.autoSync(force: true)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(store.loadToken())
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertEqual(viewModel.statusMessage, UserSession.reloginMessage)
    }

    func test_dashboardSync_whenSignedOut_makesNoRequest() async {
        let store = InMemoryTokenStore()
        let session = UserSession(defaults: defaults, tokenStore: store, watchPublisher: NoopWatchPublisher())
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(tokenStore: store),
            userSession: session
        )
        StubURLProtocol.setStub(status: 200, json: Fixture.syncResponse)
        viewModel.useMockData = true

        await viewModel.autoSync(force: true)

        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertNil(viewModel.snapshot)
        XCTAssertEqual(viewModel.statusMessage, UserSession.signInPrompt)
    }

    private func decodeAuth(_ json: String) throws -> AuthTokenResponse {
        try JSONDecoder().decode(AuthTokenResponse.self, from: Data(json.utf8))
    }
}
