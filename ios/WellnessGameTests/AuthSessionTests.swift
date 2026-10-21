import XCTest

// MARK: - Refresh

@MainActor
final class AuthRefreshTests: XCTestCase {
    private var defaults: UserDefaults!
    private var accessStore: InMemoryTokenStore!
    private var refreshStore: InMemoryTokenStore!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        accessStore = InMemoryTokenStore()
        refreshStore = InMemoryTokenStore()
        credentials = SessionCredentialStore(accessStore: accessStore, refreshStore: refreshStore, defaults: defaults)
        clock = TestClock(Date(timeIntervalSince1970: 1_900_000_000))
    }

    private func makeClient() -> NetworkClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RoutedURLProtocol.self]
        let clock = clock!
        return NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            credentials: credentials,
            now: { clock.now }
        )
    }

    private func makeSession(
        publisher: WatchAuthPublishing = NoopWatchPublisher(),
        watchTokenRetryDelay: TimeInterval = 30
    ) -> UserSession {
        let clock = clock!
        return UserSession(
            defaults: defaults,
            credentials: credentials,
            watchPublisher: publisher,
            watchTokenRetryDelay: watchTokenRetryDelay,
            now: { clock.now }
        )
    }

    /// access-1은 401, access-2는 200. refresh-1 → (access-2, refresh-2)로 회전.
    private func respondWithRotation(refreshStatus: Int = 200, refreshDelay: TimeInterval = 0) {
        RoutedURLProtocol.route { request in
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
        let refreshes = RoutedURLProtocol.requests(path: AuthV2.refreshPath)
        XCTAssertEqual(refreshes.count, 1)
        XCTAssertEqual(RoutedURLProtocol.stringFields(of: refreshes[0]), ["refreshToken": "refresh-1"])
        XCTAssertNil(AuthV2.bearer(refreshes[0]), "리프레시는 공개 경로라 Bearer를 싣지 않는다")
        XCTAssertEqual(
            RoutedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer),
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
        RoutedURLProtocol.route { request in
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
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.syncPath).count, 2)
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
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
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
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
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.refreshPath).count, 1)
    }

    func test_tokenAboutToExpire_isRefreshedBeforeRequest() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        respondWithRotation()
        clock.advance(3600 - 30)

        _ = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [AuthV2.refreshPath, AuthV2.syncPath])
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer), ["Bearer access-2"])
    }

    func test_tokenNotNearExpiry_isNotRefreshedEarly() async throws {
        try credentials.save(AuthV2.login(access: "access-2").sessionTokens, now: clock.now)
        respondWithRotation()
        clock.advance(3600 - 120)

        _ = try await makeClient().sync(AuthV2.syncRequest())

        XCTAssertTrue(RoutedURLProtocol.requests(path: AuthV2.refreshPath).isEmpty)
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
        RoutedURLProtocol.route { request in
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
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.syncPath).map(AuthV2.bearer), ["Bearer legacy"])

        accept = false
        await viewModel.autoSync(force: true)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertTrue(RoutedURLProtocol.requests(path: AuthV2.refreshPath).isEmpty, "리프레시 토큰이 없으면 리프레시하지 않는다")
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

    func test_logOut_sendsOnlyRefreshToken_withoutAuthorization() async throws {
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        // 액세스 토큰이 만료돼도 갱신 없이 바로 폐기를 요청한다.
        clock.advance(7200)
        RoutedURLProtocol.route { _ in .init(status: 204, json: "") }

        try await makeClient().logOut()

        XCTAssertEqual(RoutedURLProtocol.requests.map { $0.url?.path }, [AuthV2.logoutPath])
        let request = try XCTUnwrap(RoutedURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertNil(AuthV2.bearer(request), "로그아웃은 무인증 공개 경로다")
        XCTAssertEqual(RoutedURLProtocol.stringFields(of: request), ["refreshToken": "refresh-1"])
    }

    func test_logOut_withoutRefreshToken_sendsNothing() async throws {
        try credentials.save(AuthV2.login(refresh: nil).sessionTokens, now: clock.now)
        RoutedURLProtocol.route { _ in .init(status: 204, json: "") }

        try await makeClient().logOut()

        XCTAssertTrue(RoutedURLProtocol.requests.isEmpty)
    }

    func test_refreshSaveFailure_keepsPreviousRefreshToken_andNextRequestRecovers() async throws {
        let flaky = FlakyTokenStore()
        credentials = SessionCredentialStore(accessStore: accessStore, refreshStore: flaky, defaults: defaults)
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        flaky.failNextSave = true
        respondWithRotation()
        let client = makeClient()

        do {
            _ = try await client.sync(AuthV2.syncRequest())
            XCTFail("회전 토큰 저장 실패는 에러가 나야 합니다")
        } catch {
            XCTAssertTrue(error is KeychainError, "\(error)")
        }
        XCTAssertEqual(flaky.loadToken(), "refresh-1", "저장 실패 시 이전 리프레시 토큰을 유지한다")
        XCTAssertEqual(accessStore.loadToken(), "access-1")

        // 서버 유예 구간(30초) 안에 이전 토큰으로 다시 리프레시하면 복구된다.
        let response = try await client.sync(AuthV2.syncRequest())

        XCTAssertEqual(response.gainedXp, 10)
        let refreshes = RoutedURLProtocol.requests(path: AuthV2.refreshPath)
        XCTAssertEqual(refreshes.compactMap { RoutedURLProtocol.stringFields(of: $0)?["refreshToken"] }, ["refresh-1", "refresh-1"])
        XCTAssertEqual(flaky.loadToken(), "refresh-2")
        XCTAssertEqual(accessStore.loadToken(), "access-2")
    }

    func test_signOutFlow_clearsLocalSession_evenWhenServerFails() async throws {
        let session = makeSession()
        try session.signIn(provider: .kakao, auth: AuthV2.login())
        RoutedURLProtocol.route { _ in .init(status: 500, json: #"{"message":"down"}"#) }
        var signedOutProvider: LoginProvider?

        await SocialSignInFlow(networkClient: makeClient()).signOut(session: session) { signedOutProvider = $0 }

        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.logoutPath).count, 1)
        XCTAssertEqual(signedOutProvider, .kakao)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice)
        XCTAssertNil(accessStore.loadToken())
        XCTAssertNil(refreshStore.loadToken())
    }

    // MARK: Watch token

    private func respondWithWatchTokens(_ tokens: [String?]) {
        var remaining = tokens
        RoutedURLProtocol.route { request in
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

        let first = try XCTUnwrap(spy.lastContext)
        XCTAssertEqual(first.accessToken, "watch-1")
        XCTAssertEqual(first.userId, "password:alice")
        XCTAssertEqual(first.displayName, "Alice")
        XCTAssertEqual(first.expiresAt, clock.now.addingTimeInterval(604_800))
        let issue = try XCTUnwrap(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).first)
        XCTAssertEqual(AuthV2.bearer(issue), "Bearer access-1", "watch-token은 전체 권한 토큰으로 발급받는다")

        // 만료까지 1일 넘게 남았으면 다시 발급하지 않는다(동기화 간격과 무관).
        clock.advance(604_800 - 86_401)
        try credentials.save(AuthV2.login(access: "access-1").sessionTokens, now: clock.now)
        await viewModel.autoSync()
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 1)

        // 1일 이내로 들어오면 재발급해 다시 보낸다.
        clock.advance(2)
        try credentials.save(AuthV2.login(access: "access-1").sessionTokens, now: clock.now)
        await viewModel.autoSync()
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertEqual(spy.lastContext?.accessToken, "watch-2")
    }

    func test_watchTokenFailure_keepsPreviousValue() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens(["watch-1"])
        let client = makeClient()

        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }
        let publishedCount = spy.published.count
        clock.advance(604_800 - 60)
        try credentials.save(AuthV2.login().sessionTokens, now: clock.now)
        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }

        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertEqual(spy.published.count, publishedCount, "실패하면 빈 값이나 새 값을 보내지 않는다")
        XCTAssertEqual(spy.lastContext?.accessToken, "watch-1")
        XCTAssertTrue(session.isSignedIn)
    }

    func test_watchTokenFailureRightAfterSignIn_isRetriedOnceAfterDelay() async throws {
        let spy = SpyWatchPublisher()
        let session = makeSession(publisher: spy, watchTokenRetryDelay: 0.01)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens([nil, "watch-1"])
        let client = makeClient()

        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }
        XCTAssertNotNil(session.watchTokenRetryTask)
        await session.watchTokenRetryTask?.value

        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertEqual(spy.lastContext?.accessToken, "watch-1")
    }

    func test_watchTokenRetryAfterSignIn_happensOnlyOnce() async throws {
        let session = makeSession(watchTokenRetryDelay: 0.01)
        try session.signIn(provider: .password, auth: AuthV2.login())
        respondWithWatchTokens([])
        let client = makeClient()

        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }
        let retry = try XCTUnwrap(session.watchTokenRetryTask)
        await retry.value
        try await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 2)
        XCTAssertTrue(session.isSignedIn)

        // 이후 실패는 지연 재시도 없이 다음 포그라운드에서 다시 시도한다.
        await session.refreshWatchTokenIfNeeded { try await client.issueWatchToken() }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(RoutedURLProtocol.requests(path: AuthV2.watchTokenPath).count, 3)
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

        XCTAssertEqual(spy.lastContext?.accessToken, "watch-2")
    }
}

// MARK: - Apple nonce / fullName

@MainActor
final class AppleSignInV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var session: UserSession!
    private var nameBacking: InMemoryTokenStore!
    private var flow: SocialSignInFlow!

    override func setUp() {
        super.setUp()
        defaults = ScratchDefaults.make(for: self)
        session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: NoopWatchPublisher())
        nameBacking = InMemoryTokenStore()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RoutedURLProtocol.self]
        flow = SocialSignInFlow(
            networkClient: NetworkClient(
                baseURL: URL(string: "http://stub.local")!,
                session: URLSession(configuration: configuration)
            ),
            pendingAppleNames: PendingAppleNameStore(backing: nameBacking)
        )
    }

    private let success = """
    {"userId":"apple:1","displayName":"홍길동","accessToken":"jwt-apple","tokenType":"Bearer","expiresIn":3600,
     "refreshToken":"refresh-apple","refreshExpiresIn":2592000}
    """

    private func lastAppleBody() throws -> [String: String] {
        let request = try XCTUnwrap(RoutedURLProtocol.requests(path: "/api/auth/apple/native").last)
        return try XCTUnwrap(RoutedURLProtocol.stringFields(of: request))
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
        RoutedURLProtocol.route { _ in .init(status: 200, json: self.success) }

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "id-token", fullName: nil, userIdentifier: "u1"),
            nonce: "raw-nonce",
            session: session
        )

        XCTAssertEqual(try lastAppleBody(), ["identityToken": "id-token", "nonce": "raw-nonce"])
        XCTAssertTrue(session.isSignedIn)
    }

    func test_fullName_isKeptAfterFailedExchange_resentNextTime_andRemovedOnSuccess() async throws {
        RoutedURLProtocol.route { _ in .init(status: 500, json: #"{"message":"down"}"#) }
        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "t1", fullName: "길동 홍", userIdentifier: "u1"),
            nonce: "n1",
            session: session
        )
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNotNil(nameBacking.loadToken(), "교환 실패 시 이름을 Keychain에 보관한다")

        // Apple은 두 번째 인증부터 이름을 주지 않는다.
        RoutedURLProtocol.route { _ in .init(status: 200, json: self.success) }
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
        RoutedURLProtocol.route { _ in .init(status: 200, json: self.success) }

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
        RoutedURLProtocol.route { _ in .init(status: 200, json: self.success) }

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
