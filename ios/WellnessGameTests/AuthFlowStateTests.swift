import AuthenticationServices
import XCTest

// MARK: - UserSession 상태

@MainActor
final class UserSessionStateTests: XCTestCase {
    private var defaults: UserDefaults!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: InMemoryTokenStore(), defaults: defaults)
        clock = TestClock(kst("2026-10-21 09:00"))
    }

    private func makeSession(publisher: WatchAuthPublishing = NoopWatchPublisher()) -> UserSession {
        let clock = clock!
        return UserSession(
            defaults: defaults,
            credentials: credentials,
            watchPublisher: publisher,
            watchTokenRetryDelay: 3600,
            now: { clock.now }
        )
    }

    private func auth(userId: String = "kakao:1", displayName: String?, access: String = "a") -> AuthTokenResponse {
        AuthTokenResponse(
            userId: userId, displayName: displayName, accessToken: access, tokenType: "Bearer",
            expiresIn: 3600, userIdentifier: nil, refreshToken: "r", refreshExpiresIn: 2_592_000
        )
    }

    func test_계정표시는_로그아웃이면_로그인필요_이름이_없으면_공급자명을_쓴다() throws {
        let session = makeSession()
        XCTAssertEqual(session.accountLabel, String(localized: "로그인 필요"))

        try session.signIn(provider: .kakao, auth: auth(displayName: "홍길동"))
        XCTAssertEqual(session.accountLabel, "홍길동")

        try session.signIn(provider: .kakao, auth: auth(displayName: ""))
        XCTAssertNil(session.displayName, "빈 이름은 이름이 없는 것으로 본다")
        XCTAssertTrue(session.accountLabel.contains("Kakao"), session.accountLabel)
    }

    func test_이름없는_계정으로_다시_로그인하면_이전_계정의_이름이_복원되지_않는다() throws {
        try makeSession().signIn(provider: .kakao, auth: auth(userId: "kakao:1", displayName: "이전 사용자"))
        try makeSession().signIn(provider: .naver, auth: auth(userId: "naver:2", displayName: nil))

        let restored = makeSession()

        XCTAssertEqual(restored.userId, "naver:2")
        XCTAssertEqual(restored.provider, .naver)
        XCTAssertNil(restored.displayName)
    }

    func test_토큰저장에_실패하면_로그인상태로_바꾸지_않는다() throws {
        let failing = FlakyTokenStore()
        failing.failNextSave = true
        credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: failing, defaults: defaults)
        let session = makeSession()

        XCTAssertThrowsError(try session.signIn(provider: .apple, auth: auth(displayName: "A")))

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(defaults.string(forKey: "auth.userId"))
    }

    func test_만료정보가_없는_Watch토큰은_만료없이_보내고_다시_발급하지_않는다() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .password, auth: auth(displayName: "Alice"))
        var issued = 0

        for _ in 0..<2 {
            await session.refreshWatchTokenIfNeeded {
                issued += 1
                return WatchTokenResponse(accessToken: "watch", tokenType: "Bearer", expiresIn: 0)
            }
        }

        XCTAssertEqual(issued, 1)
        let context = try XCTUnwrap(spy.lastContext)
        XCTAssertNil(context.expiresAt)
        XCTAssertNil(defaults.object(forKey: "auth.watchToken.expiresAt"))
    }

    func test_Watch토큰_발급중_다른계정으로_바뀌면_늦게온_401로_새세션을_지우지_않는다() async throws {
        let session = makeSession()
        try session.signIn(provider: .kakao, auth: auth(userId: "kakao:1", displayName: "A"))

        await session.refreshWatchTokenIfNeeded { [session] in
            try session.signIn(provider: .naver, auth: self.auth(userId: "naver:2", displayName: "B", access: "b"))
            throw NetworkError.unauthorized(message: "")
        }

        XCTAssertEqual(session.userId, "naver:2")
        XCTAssertNil(session.reloginNotice)
        XCTAssertEqual(credentials.accessToken, "b")
    }

    func test_Watch토큰_발급중_로그아웃하면_발급결과를_Watch에_보내지_않는다() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .kakao, auth: auth(displayName: "A"))
        let publishedBefore = spy.published.count

        await session.refreshWatchTokenIfNeeded { [session] in
            session.signOut()
            return WatchTokenResponse(accessToken: "watch", tokenType: "Bearer", expiresIn: 3600)
        }

        XCTAssertEqual(spy.published.count, publishedBefore + 1)
        XCTAssertNil(spy.lastContext, "로그아웃의 빈 컨텍스트만 보낸다")
    }
}

// MARK: - 아이디/비밀번호 인증

@MainActor
final class CredentialAuthServiceTests: XCTestCase {
    private var defaults: UserDefaults!
    private var session: UserSession!
    private var service: CredentialAuthService!

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        let credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: InMemoryTokenStore(), defaults: defaults)
        session = UserSession(defaults: defaults, credentials: credentials, watchPublisher: NoopWatchPublisher())
        service = CredentialAuthService(networkClient: NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: RoutedURLProtocol.makeSession(),
            credentials: credentials
        ))
    }

    func test_회원가입에_성공하면_계정세션으로_로그인한다() async throws {
        RoutedURLProtocol.route { _ in .http(status: 200, json: BackendFixture.login(access: "a-1", refresh: "r-1")) }

        await service.signUp(username: "alice", password: "password1", into: session)

        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [BackendFixture.signUpPath])
        XCTAssertEqual(session.provider, .password)
        XCTAssertEqual(session.userId, "password:alice")
        XCTAssertNil(service.errorMessage)
        XCTAssertFalse(service.isLoading)
    }

    func test_잘못된_비밀번호면_서버메시지를_보여주고_로그아웃상태를_유지한다() async {
        RoutedURLProtocol.route { _ in .http(status: 401, json: BackendFixture.error(401, "아이디 또는 비밀번호가 올바르지 않습니다.")) }

        await service.logIn(username: "alice", password: "wrong", into: session)

        XCTAssertEqual(service.errorMessage, "아이디 또는 비밀번호가 올바르지 않습니다.")
        XCTAssertEqual(session.statusMessage, "아이디 또는 비밀번호가 올바르지 않습니다.")
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice, "로그인 실패는 세션 만료 안내가 아니다")
        XCTAssertFalse(service.isLoading)
    }

    func test_다시_시도하면_이전_오류문구를_지운다() async {
        RoutedURLProtocol.route { _ in .http(status: 409, json: BackendFixture.error(409, "이미 사용 중인 아이디입니다.")) }
        await service.signUp(username: "alice", password: "password1", into: session)
        XCTAssertNotNil(service.errorMessage)

        RoutedURLProtocol.route { _ in .http(status: 200, json: BackendFixture.login(access: "a", refresh: "r")) }
        await service.logIn(username: "alice", password: "password1", into: session)

        XCTAssertNil(service.errorMessage)
        XCTAssertTrue(session.isSignedIn)
    }
}

// MARK: - Apple 로그인 결과 처리

@MainActor
final class AppleSignInResultTests: XCTestCase {
    private var defaults: UserDefaults!
    private var session: UserSession!
    private var flow: SocialSignInFlow!

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())
        flow = SocialSignInFlow(
            networkClient: NetworkClient(baseURL: URL(string: "http://stub.local")!, session: RoutedURLProtocol.makeSession()),
            pendingAppleNames: PendingAppleNameStore(backing: InMemoryTokenStore())
        )
        RoutedURLProtocol.route { _ in .http(status: 500, json: "") }
    }

    func test_사용자가_취소하면_요청없이_로그인안내로_돌아간다() async {
        await flow.signInWithApple(result: .failure(ASAuthorizationError(.canceled)), nonce: "n", session: session)

        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
        XCTAssertEqual(session.statusMessage, UserSession.signInPrompt)
        XCTAssertFalse(session.isSignedIn)
    }

    func test_Apple인증_오류는_요청없이_실패문구를_보여준다() async {
        await flow.signInWithApple(result: .failure(ASAuthorizationError(.failed)), nonce: "n", session: session)

        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
        XCTAssertNotEqual(session.statusMessage, UserSession.signInPrompt)
        XCTAssertTrue(session.statusMessage.contains("Apple"), session.statusMessage)
        XCTAssertFalse(session.isSignedIn)
    }

    func test_교환이_401이면_서버메시지를_보여주고_보관된_이름을_남긴다() async {
        RoutedURLProtocol.route { _ in .http(status: 401, json: BackendFixture.error(401, "nonce가 일치하지 않습니다.")) }
        let names = PendingAppleNameStore(backing: InMemoryTokenStore())
        let flow = SocialSignInFlow(
            networkClient: NetworkClient(baseURL: URL(string: "http://stub.local")!, session: RoutedURLProtocol.makeSession()),
            pendingAppleNames: names
        )

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "id", fullName: "홍 길동", userIdentifier: "apple-1"),
            nonce: "n",
            session: session
        )

        XCTAssertTrue(session.statusMessage.contains("nonce가 일치하지 않습니다."), session.statusMessage)
        XCTAssertEqual(names.fullName(for: "apple-1"), "홍 길동")
        XCTAssertNil(session.reloginNotice)
    }
}

// MARK: - 대시보드 상태

@MainActor
final class DashboardViewModelStateTests: XCTestCase {
    private var defaults: UserDefaults!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!
    private var session: UserSession!
    private var provider: ClockedHealthProvider!

    override func setUp() async throws {
        defaults = ScratchDefaults.make(for: self)
        credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: InMemoryTokenStore(), defaults: defaults)
        clock = TestClock(kst("2026-10-21 09:00"))
        let clock = clock!
        session = UserSession(
            defaults: defaults, credentials: credentials, watchPublisher: NoopWatchPublisher(),
            watchTokenRetryDelay: 3600, now: { clock.now }
        )
        provider = ClockedHealthProvider(clock: clock)
    }

    private func signIn() throws {
        try session.signIn(provider: .password, auth: AuthTokenResponse(
            userId: "password:alice", displayName: "Alice", accessToken: "access-1", tokenType: "Bearer",
            expiresIn: 3600, userIdentifier: "alice", refreshToken: "refresh-1", refreshExpiresIn: 2_592_000
        ))
    }

    private func makeViewModel(onAuthorization: @escaping @MainActor () async -> Void = {}) -> DashboardViewModel {
        let clock = clock!
        let client = NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: RoutedURLProtocol.makeSession(),
            credentials: credentials,
            now: { clock.now }
        )
        let viewModel = DashboardViewModel(
            appleProvider: provider,
            mockProvider: provider,
            networkClient: client,
            userSession: session,
            syncService: HealthSyncService(
                networkClient: client,
                userSession: session,
                history: HealthSyncHistory(defaults: defaults),
                calendar: seoulCalendar,
                serverCalendar: seoulCalendar,
                now: { clock.now }
            ),
            onHealthAuthorizationRequested: onAuthorization
        )
        return viewModel
    }

    private func route(syncStatus: Int = 200, levelUp: Bool = false) {
        RoutedURLProtocol.route { request in
            switch request.url?.path {
            case BackendFixture.syncPath:
                return syncStatus == 200
                    ? .http(status: 200, json: BackendFixture.syncResponse(level: levelUp ? 2 : 1, levelUp: levelUp))
                    : .http(status: syncStatus, json: BackendFixture.error(syncStatus, "점검 중입니다."))
            case BackendFixture.watchTokenPath:
                return .http(status: 200, json: BackendFixture.watchToken("watch"))
            default:
                return .http(status: 404, json: "")
            }
        }
    }

    func test_로그인전에는_요청없이_로그인안내를_세션만료후에는_재로그인안내를_보여준다() async throws {
        route()
        let viewModel = makeViewModel()

        await viewModel.autoSync()
        XCTAssertEqual(viewModel.statusMessage, UserSession.signInPrompt)

        try signIn()
        session.handleUnauthorized()
        await viewModel.autoSync(force: true)

        XCTAssertEqual(viewModel.statusMessage, UserSession.reloginMessage)
        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
        XCTAssertEqual(provider.authorizationRequests, 0, "로그인하지 않았으면 HealthKit 권한도 묻지 않는다")
    }

    func test_동기화중에는_로딩이고_끝나면_스냅샷과_응답_완료문구를_채운다() async throws {
        try signIn()
        route()
        var authorizationCallbacks = 0
        let viewModel = makeViewModel { authorizationCallbacks += 1 }
        provider.holdToday = true
        let loading = expectation(description: "오늘 데이터 읽기 시작")
        provider.todayLoadStarted = loading

        let running = Task { await viewModel.autoSync() }
        await fulfillment(of: [loading], timeout: 5)
        XCTAssertTrue(viewModel.isLoading)
        XCTAssertNil(viewModel.syncResponse)
        provider.releaseToday()
        await running.value

        XCTAssertFalse(viewModel.isLoading)
        XCTAssertEqual(viewModel.snapshot?.steps, 8_500)
        XCTAssertEqual(viewModel.syncResponse?.character.level, 1)
        XCTAssertEqual(viewModel.statusMessage, String(localized: "자동 동기화 완료"))
        XCTAssertEqual(authorizationCallbacks, 1)
        XCTAssertEqual(provider.authorizationRequests, 1)
    }

    func test_레벨업_응답이면_레벨업_문구를_보여준다() async throws {
        try signIn()
        route(levelUp: true)
        let viewModel = makeViewModel()

        await viewModel.autoSync()

        XCTAssertEqual(viewModel.statusMessage, String(localized: "레벨업! 성장 결과를 확인하세요."))
        XCTAssertEqual(viewModel.syncResponse?.character.level, 2)
    }

    func test_서버오류면_읽은_스냅샷은_보여주고_재시도_안내와_원인을_보여준다() async throws {
        try signIn()
        route(syncStatus: 503)
        let viewModel = makeViewModel()

        await viewModel.autoSync()

        XCTAssertTrue(session.isSignedIn, "서버 오류는 로그아웃 사유가 아니다")
        XCTAssertNotNil(viewModel.snapshot, "요청 전에 읽은 오늘 데이터는 화면에 남는다")
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertTrue(viewModel.statusMessage.contains("점검 중입니다."), viewModel.statusMessage)
        XCTAssertFalse(viewModel.isLoading)
    }

    func test_최소간격_안의_자동동기화는_건너뛰고_당겨서_새로고침은_다시_보낸다() async throws {
        try signIn()
        route()
        let viewModel = makeViewModel()

        await viewModel.autoSync()
        await viewModel.autoSync()
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.syncPath).count, 1)

        await viewModel.autoSync(force: true)
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.syncPath).count, 2)
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.watchTokenPath).count, 1, "Watch 토큰은 만료가 가까울 때만 다시 발급한다")
    }

    func test_초기화하면_이전_사용자_화면데이터를_비우고_다음_자동동기화는_간격과_무관하게_보낸다() async throws {
        try signIn()
        route()
        let viewModel = makeViewModel()
        await viewModel.autoSync()

        viewModel.reset()
        XCTAssertNil(viewModel.snapshot)
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertEqual(viewModel.statusMessage, UserSession.signInPrompt)

        await viewModel.autoSync()
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.syncPath).count, 2)
    }
}
