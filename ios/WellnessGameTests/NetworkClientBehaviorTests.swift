import XCTest

// MARK: - Test doubles

/// 요청마다 HTTP 응답·네트워크 오류·HTTP가 아닌 응답 중 하나를 고르는 URLProtocol stub.
/// 기존 `ScriptedURLProtocol`과 달리 전송 실패와 비 HTTP 응답도 흉내 낼 수 있다.
final class RoutedURLProtocol: URLProtocol {
    enum Reply {
        case http(status: Int, json: String)
        case failure(URLError.Code)
        case nonHTTP
    }

    private static let lock = NSLock()
    private static var _handler: (URLRequest) -> Reply = { _ in .http(status: 404, json: "") }
    private static var _requests: [URLRequest] = []

    static func route(_ handler: @escaping (URLRequest) -> Reply) {
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

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RoutedURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    static func json(of request: URLRequest) -> [String: Any]? {
        guard let data = StubURLProtocol.bodyData(of: request) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let reply = Self.lock.withLock { () -> Reply in
            Self._requests.append(request)
            return Self._handler(request)
        }
        switch reply {
        case let .http(status, json):
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(json.utf8))
            client?.urlProtocolDidFinishLoading(self)
        case let .failure(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .nonHTTP:
            let response = URLResponse(
                url: request.url!,
                mimeType: "application/json",
                expectedContentLength: 2,
                textEncodingName: nil
            )
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("{}".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

/// 백엔드 DTO(`com.wellnessgame.api`)와 같은 모양의 응답 JSON.
enum BackendFixture {
    static let loginPath = "/api/auth/login"
    static let signUpPath = "/api/auth/signup"
    static let syncPath = "/api/health-activities/sync"
    static let refreshPath = "/api/auth/refresh"
    static let logoutPath = "/api/auth/logout"
    static let watchTokenPath = "/api/auth/watch-token"

    /// `HealthActivitySyncResponse`(goals 포함, 클라이언트가 모르는 필드도 섞여 있다).
    static func syncResponse(
        date: String = "2026-10-21",
        level: Int = 1,
        currentXp: Int = 40,
        totalXp: Int = 40,
        nextLevelXp: Int = 100,
        gainedXp: Int = 40,
        levelUp: Bool = false
    ) -> String {
        """
        {"userId":"password:alice","date":"\(date)","gainedXp":\(gainedXp),"levelUp":\(levelUp),
         "character":{"level":\(level),"currentXp":\(currentXp),"totalXp":\(totalXp),"nextLevelXp":\(nextLevelXp),
           "stats":{"str":3,"vit":2,"intStat":1,"discipline":4,"recovery":5}},
         "activityResults":[
           {"type":"STEPS","source":"HEALTHKIT","gainedXp":30,"message":"걸음 8500보","duplicate":false},
           {"type":"SLEEP","source":"HEALTHKIT","gainedXp":10,"message":"수면 7시간","duplicate":true}],
         "goals":[{"type":"STEPS","target":8000,"current":8500,"unit":"steps","achieved":true}]}
        """
    }

    static func login(access: String, refresh: String?, expiresIn: Int = 3600) -> String {
        let refreshFields = refresh.map { #","refreshToken":"\#($0)","refreshExpiresIn":2592000"# } ?? ""
        return """
        {"userIdentifier":"alice","userId":"password:alice","displayName":"Alice",
         "accessToken":"\(access)","tokenType":"Bearer","expiresIn":\(expiresIn)\(refreshFields)}
        """
    }

    static func refreshed(access: String, refresh: String, expiresIn: Int = 3600) -> String {
        """
        {"userId":"password:alice","accessToken":"\(access)","tokenType":"Bearer","expiresIn":\(expiresIn),
         "refreshToken":"\(refresh)","refreshExpiresIn":2592000}
        """
    }

    static func watchToken(_ token: String) -> String {
        #"{"accessToken":"\#(token)","tokenType":"Bearer","expiresIn":604800}"#
    }

    static func error(_ status: Int, _ message: String) -> String {
        #"{"timestamp":"2026-10-21T00:00:00Z","status":\#(status),"error":"Error","message":"\#(message)"}"#
    }

    static func bearer(_ request: URLRequest) -> String? {
        request.value(forHTTPHeaderField: "Authorization")
    }
}

// MARK: - 요청 구성·오류 변환

final class NetworkClientRequestBuildingTests: XCTestCase {
    private func makeClient(
        baseURL: String = "http://stub.local",
        credentials: SessionCredentialStore? = nil
    ) -> NetworkClient {
        NetworkClient(
            baseURL: URL(string: baseURL)!,
            session: RoutedURLProtocol.makeSession(),
            credentials: credentials
        )
    }

    func test_동기화요청은_POST_JSON으로_보내고_날짜는_초단위_ISO8601_빈값은_생략한다() async throws {
        RoutedURLProtocol.route { _ in .http(status: 200, json: BackendFixture.syncResponse()) }
        let start = Date(timeIntervalSince1970: 1_792_540_800) // 2026-10-21T00:00:00Z
        let request = HealthActivitySyncRequest(
            userId: "password:alice",
            date: "2026-10-21",
            activities: [
                HealthActivityDTO(type: .steps, steps: 8500),
                HealthActivityDTO(
                    type: .workout,
                    workoutType: .strengthTraining,
                    durationMinutes: 30,
                    calories: 120.5,
                    startedAt: start,
                    endedAt: start.addingTimeInterval(1800)
                ),
            ]
        )

        _ = try await makeClient().sync(request)

        let sent = try XCTUnwrap(RoutedURLProtocol.requests.last)
        XCTAssertEqual(sent.httpMethod, "POST")
        XCTAssertEqual(sent.url?.absoluteString, "http://stub.local/api/health-activities/sync")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Accept-Language"), AppLanguage.acceptLanguage())
        let json = try XCTUnwrap(RoutedURLProtocol.json(of: sent))
        XCTAssertEqual(json["userId"] as? String, "password:alice")
        XCTAssertEqual(json["date"] as? String, "2026-10-21")
        let activities = try XCTUnwrap(json["activities"] as? [[String: Any]])
        // 걸음 항목에는 값이 없는 필드(칼로리·시각 등)와 클라이언트 전용 id를 싣지 않는다.
        XCTAssertEqual(Set(activities[0].keys), ["type", "steps"])
        XCTAssertEqual(activities[0]["type"] as? String, "STEPS")
        XCTAssertEqual(activities[1]["workoutType"] as? String, "STRENGTH_TRAINING")
        XCTAssertEqual(activities[1]["startedAt"] as? String, "2026-10-21T00:00:00Z")
        XCTAssertEqual(activities[1]["endedAt"] as? String, "2026-10-21T00:30:00Z")
        XCTAssertEqual(activities[1]["calories"] as? Double, 120.5)
    }

    func test_기본주소에_경로접두사가_있으면_그대로_유지한다() async throws {
        RoutedURLProtocol.route { _ in .http(status: 200, json: BackendFixture.login(access: "a", refresh: "r")) }

        _ = try await makeClient(baseURL: "https://stub.local/wellness").logIn(
            LoginRequest(username: "alice", password: "password1")
        )

        XCTAssertEqual(RoutedURLProtocol.requests.last?.url?.absoluteString, "https://stub.local/wellness/api/auth/login")
    }

    func test_회원가입은_저장된토큰을_싣지않고_표시이름이_없으면_필드를_생략한다() async throws {
        RoutedURLProtocol.route { _ in .http(status: 200, json: BackendFixture.login(access: "a-1", refresh: "r-1")) }
        let defaults = UserDefaults(suiteName: "NetworkClientRequestBuildingTests.\(UUID().uuidString)")!
        let credentials = SessionCredentialStore(
            accessStore: InMemoryTokenStore(token: "stale"),
            refreshStore: InMemoryTokenStore(token: "stale-refresh"),
            defaults: defaults
        )

        let response = try await makeClient(credentials: credentials).signUp(
            SignUpRequest(username: "alice", password: "password1", displayName: nil)
        )

        let sent = try XCTUnwrap(RoutedURLProtocol.requests.last)
        XCTAssertEqual(sent.url?.path, BackendFixture.signUpPath)
        XCTAssertNil(BackendFixture.bearer(sent))
        let json = try XCTUnwrap(RoutedURLProtocol.json(of: sent))
        XCTAssertEqual(json as NSDictionary, ["username": "alice", "password": "password1"] as NSDictionary)
        XCTAssertEqual(response.refreshToken, "r-1")
        XCTAssertEqual(response.refreshExpiresIn, 2_592_000)
        XCTAssertEqual(response.sessionTokens, SessionTokens(
            accessToken: "a-1", expiresIn: 3600, refreshToken: "r-1", refreshExpiresIn: 2_592_000
        ))
    }

    func test_로그아웃은_짧은_타임아웃으로_리프레시토큰만_보낸다() async throws {
        RoutedURLProtocol.route { _ in .http(status: 204, json: "") }
        let defaults = UserDefaults(suiteName: "NetworkClientRequestBuildingTests.\(UUID().uuidString)")!
        let credentials = SessionCredentialStore(
            accessStore: InMemoryTokenStore(token: "access"),
            refreshStore: InMemoryTokenStore(token: "refresh"),
            defaults: defaults
        )

        try await makeClient(credentials: credentials).logOut()

        let sent = try XCTUnwrap(RoutedURLProtocol.requests(path: BackendFixture.logoutPath).first)
        XCTAssertEqual(sent.timeoutInterval, NetworkClient.logoutTimeout)
        XCTAssertNil(BackendFixture.bearer(sent))
        XCTAssertEqual(RoutedURLProtocol.json(of: sent) as NSDictionary?, ["refreshToken": "refresh"] as NSDictionary)
    }

    func test_인증저장소가_없으면_보호API도_토큰없이_보내고_401에_리프레시를_시도하지_않는다() async {
        RoutedURLProtocol.route { _ in .http(status: 401, json: BackendFixture.error(401, "")) }

        do {
            _ = try await makeClient().issueWatchToken()
            XCTFail("401은 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: ""))
        }
        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [BackendFixture.watchTokenPath])
        XCTAssertNil(RoutedURLProtocol.requests.first.flatMap(BackendFixture.bearer))
        // Watch 토큰 발급은 본문 없는 `{}`를 보낸다.
        XCTAssertEqual(RoutedURLProtocol.requests.first.flatMap(RoutedURLProtocol.json(of:))?.isEmpty, true)
    }

    func test_HTTP가_아닌_응답은_invalidResponse로_바꾼다() async {
        RoutedURLProtocol.route { _ in .nonHTTP }

        do {
            _ = try await makeClient().logIn(LoginRequest(username: "alice", password: "password1"))
            XCTFail("HTTP 응답이 아니면 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .invalidResponse)
        }
    }

    func test_오류본문이_JSON이_아니면_본문문자열을_메시지로_쓴다() async {
        RoutedURLProtocol.route { _ in .http(status: 502, json: "Bad Gateway") }

        do {
            _ = try await makeClient().logIn(LoginRequest(username: "alice", password: "password1"))
            XCTFail("502는 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .server(status: 502, message: "Bad Gateway"))
        }
    }

    func test_전송실패는_URLError를_그대로_던진다() async {
        RoutedURLProtocol.route { _ in .failure(.notConnectedToInternet) }

        do {
            _ = try await makeClient().logIn(LoginRequest(username: "alice", password: "password1"))
            XCTFail("전송 실패는 에러가 나야 합니다")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
            XCTAssertNil(error as? NetworkError)
        }
    }

    /// 버그 기록: 오류 응답 본문이 비어 있으면 `String(data:encoding:)`이 nil이 아닌 ""을 돌려줘
    /// "알 수 없는 오류" 대체 문구가 쓰이지 않는다. 사용자에게 "서버 오류(500): "처럼 내용 없는 문구가 보인다.
    func test_오류본문이_비어있으면_알수없는오류_문구를_써야한다() async {
        RoutedURLProtocol.route { _ in .http(status: 500, json: "") }

        do {
            _ = try await makeClient().logIn(LoginRequest(username: "alice", password: "password1"))
            XCTFail("500은 에러가 나야 합니다")
        } catch {
            XCTExpectFailure("빈 오류 본문이면 메시지가 빈 문자열이 된다(대체 문구 미적용)") {
                XCTAssertEqual(error as? NetworkError, .server(status: 500, message: String(localized: "알 수 없는 오류")))
            }
        }
    }

    func test_오류문구는_서버메시지를_우선하고_없으면_재로그인_안내를_쓴다() {
        XCTAssertEqual(NetworkError.unauthorized(message: "토큰이 만료되었습니다.").errorDescription, "토큰이 만료되었습니다.")
        let fallback = NetworkError.unauthorized(message: "").errorDescription
        XCTAssertEqual(fallback, String(localized: "인증이 필요합니다. 다시 로그인해 주세요."))
        XCTAssertFalse(fallback?.isEmpty ?? true)
        XCTAssertFalse(NetworkError.invalidResponse.errorDescription?.isEmpty ?? true)

        let server = NetworkError.server(status: 409, message: "이미 사용 중인 아이디입니다.").errorDescription ?? ""
        XCTAssertTrue(server.contains("409"), server)
        XCTAssertTrue(server.contains("이미 사용 중인 아이디입니다."), server)
    }
}

// MARK: - 토큰 갱신 경계 조건

@MainActor
final class NetworkClientRefreshEdgeTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var accessStore: InMemoryTokenStore!
    private var refreshStore: InMemoryTokenStore!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!

    override func setUp() {
        super.setUp()
        suiteName = "NetworkClientRefreshEdgeTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        accessStore = InMemoryTokenStore()
        refreshStore = InMemoryTokenStore()
        credentials = SessionCredentialStore(accessStore: accessStore, refreshStore: refreshStore, defaults: defaults)
        clock = TestClock(Date(timeIntervalSince1970: 1_792_540_800))
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makeClient() -> NetworkClient {
        let clock = clock!
        return NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: RoutedURLProtocol.makeSession(),
            credentials: credentials,
            now: { clock.now }
        )
    }

    private func signIn(expiresIn: Int = 3600, refreshExpiresIn: Int? = 2_592_000) throws {
        try credentials.save(
            SessionTokens(accessToken: "access-1", expiresIn: expiresIn, refreshToken: "refresh-1", refreshExpiresIn: refreshExpiresIn),
            now: clock.now
        )
    }

    private func syncRequest() -> HealthActivitySyncRequest {
        HealthActivitySyncRequest(userId: "password:alice", date: "2026-10-21", activities: [HealthActivityDTO(type: .steps, steps: 1)])
    }

    func test_리프레시토큰도_만료됐으면_리프레시를_보내지않고_unauthorized를_던진다() async throws {
        try signIn(expiresIn: 3600, refreshExpiresIn: 7200)
        clock.advance(7200)
        RoutedURLProtocol.route { request in
            request.url?.path == BackendFixture.refreshPath
                ? .http(status: 200, json: BackendFixture.refreshed(access: "access-2", refresh: "refresh-2"))
                : .http(status: 401, json: BackendFixture.error(401, "만료"))
        }

        do {
            _ = try await makeClient().sync(syncRequest())
            XCTFail("세션이 끝났으면 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: ""))
        }
        XCTAssertTrue(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).isEmpty)
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.syncPath).count, 1)
    }

    func test_만료임박_사전갱신이_네트워크오류로_실패하면_기존토큰으로_요청한다() async throws {
        try signIn(expiresIn: 30)
        RoutedURLProtocol.route { request in
            switch request.url?.path {
            case BackendFixture.refreshPath:
                return .failure(.timedOut)
            case BackendFixture.syncPath:
                return .http(status: 200, json: BackendFixture.syncResponse())
            default:
                return .http(status: 404, json: "")
            }
        }

        _ = try await makeClient().sync(syncRequest())

        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).count, 1)
        let sync = try XCTUnwrap(RoutedURLProtocol.requests(path: BackendFixture.syncPath).first)
        XCTAssertEqual(BackendFixture.bearer(sync), "Bearer access-1")
        XCTAssertEqual(credentials.refreshToken, "refresh-1", "실패한 갱신은 저장값을 바꾸지 않는다")
    }

    func test_만료임박_사전갱신이_401이면_원래요청을_보내지않는다() async throws {
        try signIn(expiresIn: 30)
        RoutedURLProtocol.route { _ in .http(status: 401, json: BackendFixture.error(401, "재사용이 감지되었습니다.")) }

        do {
            _ = try await makeClient().sync(syncRequest())
            XCTFail("리프레시가 거절되면 에러가 나야 합니다")
        } catch {
            XCTAssertEqual(error as? NetworkError, .unauthorized(message: "재사용이 감지되었습니다."))
        }
        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [BackendFixture.refreshPath])
    }

    func test_만료된_액세스토큰은_요청전에_갱신해_새토큰만_보낸다() async throws {
        try signIn(expiresIn: 3600)
        clock.advance(4000)
        RoutedURLProtocol.route { request in
            switch request.url?.path {
            case BackendFixture.refreshPath:
                return .http(status: 200, json: BackendFixture.refreshed(access: "access-2", refresh: "refresh-2"))
            case BackendFixture.syncPath:
                return BackendFixture.bearer(request) == "Bearer access-2"
                    ? .http(status: 200, json: BackendFixture.syncResponse())
                    : .http(status: 401, json: BackendFixture.error(401, "만료"))
            default:
                return .http(status: 404, json: "")
            }
        }

        _ = try await makeClient().sync(syncRequest())

        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [BackendFixture.refreshPath, BackendFixture.syncPath])
        let refresh = try XCTUnwrap(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).first)
        XCTAssertNil(BackendFixture.bearer(refresh), "리프레시는 공개 경로라 액세스 토큰을 싣지 않는다")
        XCTAssertEqual(RoutedURLProtocol.json(of: refresh) as NSDictionary?, ["refreshToken": "refresh-1"] as NSDictionary)
        XCTAssertEqual(credentials.accessExpiresAt, clock.now.addingTimeInterval(3600))
    }

    /// 버그 기록: 리프레시가 401이 아닌 400(예: 서버 검증 실패)으로 거절되면 `NetworkError.server`로 올라가
    /// 세션이 정리되지 않는다. 쓸 수 없는 리프레시 토큰이 남아 매 요청마다 401 → 리프레시 → 400을 반복한다.
    func test_리프레시가_400으로_거절되면_세션을_정리해야_한다() async throws {
        let clock = clock!
        let session = UserSession(
            defaults: defaults,
            credentials: credentials,
            watchPublisher: NoopWatchPublisher(),
            watchTokenRetryDelay: 3600,
            now: { clock.now }
        )
        try session.signIn(provider: .password, auth: AuthTokenResponse(
            userId: "password:alice", displayName: "Alice", accessToken: "access-1", tokenType: "Bearer",
            expiresIn: 3600, userIdentifier: "alice", refreshToken: "refresh-1", refreshExpiresIn: 2_592_000
        ))
        RoutedURLProtocol.route { request in
            request.url?.path == BackendFixture.refreshPath
                ? .http(status: 400, json: BackendFixture.error(400, "refreshToken: 크기가 올바르지 않습니다."))
                : .http(status: 401, json: BackendFixture.error(401, "만료"))
        }
        let viewModel = DashboardViewModel(
            appleProvider: MockHealthDataProvider(),
            mockProvider: MockHealthDataProvider(),
            networkClient: makeClient(),
            userSession: session,
            syncService: HealthSyncService(
                networkClient: makeClient(),
                userSession: session,
                history: HealthSyncHistory(defaults: defaults)
            )
        )

        await viewModel.autoSync(force: true)

        XCTAssertFalse(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).isEmpty)
        XCTExpectFailure("리프레시 400은 server 오류로 처리돼 로그아웃되지 않는다") {
            XCTAssertFalse(session.isSignedIn)
            XCTAssertNotNil(session.reloginNotice)
        }
    }
}
