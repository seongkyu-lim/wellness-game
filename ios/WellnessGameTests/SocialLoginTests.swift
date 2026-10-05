import AuthenticationServices
import XCTest

// MARK: - LoginProviderPolicy

final class LoginProviderPolicyTests: XCTestCase {
    func test_korea_showsKakaoNaverGoogleAppleFirst() {
        let policy = LoginProviderPolicy(region: .southKorea)

        XCTAssertEqual(policy.primary, [.kakao, .naver, .google, .apple])
        XCTAssertTrue(policy.secondary.isEmpty)
    }

    func test_unitedStates_showsAppleGoogleFirst_andCollapsesKakaoNaver() {
        let policy = LoginProviderPolicy(region: .unitedStates)

        XCTAssertEqual(policy.primary, [.apple, .google])
        XCTAssertEqual(policy.secondary, [.kakao, .naver])
    }

    func test_unknownRegion_usesOverseasDefault() {
        let policy = LoginProviderPolicy(region: nil)

        XCTAssertEqual(policy.primary, [.apple, .google])
        XCTAssertEqual(policy.secondary, [.kakao, .naver])
    }

    func test_appleIsAlwaysOffered_andPasswordIsNeverListed() {
        for region in [Locale.Region.southKorea, .unitedStates, .japan, .germany, nil] {
            let all = LoginProviderPolicy(region: region).primary + LoginProviderPolicy(region: region).secondary
            XCTAssertTrue(all.contains(.apple), "\(String(describing: region))")
            XCTAssertFalse(all.contains(.password), "\(String(describing: region))")
            XCTAssertEqual(Set(all), Set(LoginProviderPolicy.socialProviders))
            XCTAssertEqual(all.count, LoginProviderPolicy.socialProviders.count, "중복 없음")
        }
    }
}

// MARK: - Token exchange

private struct FakeTokenProvider: SocialAccessTokenProviding {
    let result: Result<SocialTokens, Error>
    func fetchTokens() async throws -> SocialTokens { try result.get() }

    static func access(_ token: String) -> FakeTokenProvider {
        FakeTokenProvider(result: .success(SocialTokens(accessToken: token, refreshToken: nil)))
    }

    static func naver(access: String, refresh: String?) -> FakeTokenProvider {
        FakeTokenProvider(result: .success(SocialTokens(accessToken: access, refreshToken: refresh)))
    }
}

@MainActor
final class SocialSignInFlowTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var store: InMemoryTokenStore!
    private var session: UserSession!
    private var flow: SocialSignInFlow!

    private func response(_ id: String, token: String) -> String {
        """
        {"userId":"\(id)","displayName":"Tester","accessToken":"\(token)","tokenType":"Bearer","expiresIn":2592000}
        """
    }

    override func setUp() {
        super.setUp()
        suiteName = "SocialSignInFlowTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        store = InMemoryTokenStore()
        session = UserSession(defaults: defaults, tokenStore: store)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        // 이전 토큰이 남아 있어도 교환 요청에 실리지 않는지 확인하려고 토큰 저장소를 연결해 둔다.
        let client = NetworkClient(
            baseURL: URL(string: "http://stub.local")!,
            session: URLSession(configuration: configuration),
            tokenStore: InMemoryTokenStore(token: "stale-token")
        )
        flow = SocialSignInFlow(networkClient: client)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func lastRequestJSON() throws -> (URLRequest, [String: Any]) {
        let request = try XCTUnwrap(StubURLProtocol.requests.last)
        let body = try XCTUnwrap(StubURLProtocol.bodyData(of: request))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        return (request, json)
    }

    func test_kakao_postsAccessToken_withoutAuthorization_andStoresSession() async throws {
        StubURLProtocol.setStub(status: 200, json: response("kakao:1", token: "jwt-kakao"))

        await flow.signInWithKakao(tokenProvider: FakeTokenProvider.access("kakao-sdk-token"), session: session)

        let (request, json) = try lastRequestJSON()
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/auth/kakao/native")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(json as? [String: String], ["accessToken": "kakao-sdk-token"])
        XCTAssertEqual(session.userId, "kakao:1")
        XCTAssertEqual(session.provider, .kakao)
        XCTAssertEqual(store.loadToken(), "jwt-kakao")
    }

    func test_naver_postsAccessToken_withoutAuthorization_andStoresSession() async throws {
        StubURLProtocol.setStub(status: 200, json: response("naver:2", token: "jwt-naver"))

        await flow.signInWithNaver(tokenProvider: FakeTokenProvider.naver(access: "naver-sdk-token", refresh: "naver-refresh-token"), session: session)

        let (request, json) = try lastRequestJSON()
        XCTAssertEqual(request.url?.path, "/api/auth/naver/native")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(json as? [String: String], ["accessToken": "naver-sdk-token", "refreshToken": "naver-refresh-token"])
        XCTAssertEqual(session.userId, "naver:2")
        XCTAssertEqual(session.provider, .naver)
        XCTAssertEqual(store.loadToken(), "jwt-naver")
    }

    func test_apple_postsIdentityTokenAndFullName_andStoresSession() async throws {
        StubURLProtocol.setStub(status: 200, json: response("apple:3", token: "jwt-apple"))

        await flow.signInWithApple(
            credential: AppleCredential(identityToken: "apple-identity-token", fullName: "길동 홍"),
            session: session
        )

        let (request, json) = try lastRequestJSON()
        XCTAssertEqual(request.url?.path, "/api/auth/apple/native")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(json as? [String: String], ["identityToken": "apple-identity-token", "fullName": "길동 홍"])
        XCTAssertEqual(session.userId, "apple:3")
        XCTAssertEqual(session.provider, .apple)
        XCTAssertEqual(store.loadToken(), "jwt-apple")
    }

    func test_apple_omitsFullName_whenAbsentOrBlank() async throws {
        StubURLProtocol.setStub(status: 200, json: response("apple:3", token: "jwt-apple"))

        await flow.signInWithApple(credential: AppleCredential(identityToken: "t", fullName: "  "), session: session)

        let (_, json) = try lastRequestJSON()
        XCTAssertEqual(json as? [String: String], ["identityToken": "t"])
    }

    func test_cancelled_makesNoRequest_andShowsNoError() async {
        StubURLProtocol.setStub(status: 200, json: response("kakao:1", token: "x"))

        await flow.signInWithKakao(tokenProvider: FakeTokenProvider(result: .failure(SocialLoginError.cancelled)), session: session)

        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.statusMessage, UserSession.signInPrompt)
    }

    func test_serverFailure_keepsSignedOut_andStoresNoToken() async {
        StubURLProtocol.setStub(status: 401, json: #"{"message":"토큰이 유효하지 않습니다."}"#)

        await flow.signInWithNaver(tokenProvider: FakeTokenProvider.naver(access: "bad", refresh: "bad-refresh"), session: session)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(store.loadToken())
        XCTAssertTrue(session.statusMessage.contains("토큰이 유효하지 않습니다."))
    }

    func test_restore_keepsSocialProviderSessions() async throws {
        StubURLProtocol.setStub(status: 200, json: response("kakao:1", token: "jwt-kakao"))
        await flow.signInWithKakao(tokenProvider: FakeTokenProvider.access("t"), session: session)

        let restored = UserSession(defaults: defaults, tokenStore: store)

        XCTAssertEqual(restored.provider, .kakao)
        XCTAssertEqual(restored.userId, "kakao:1")
    }

    func test_naver_withoutRefreshToken_makesNoRequest_andShowsFailure() async {
        StubURLProtocol.setStub(status: 200, json: response("naver:2", token: "x"))

        await flow.signInWithNaver(tokenProvider: FakeTokenProvider.naver(access: "a", refresh: nil), session: session)
        await flow.signInWithNaver(tokenProvider: FakeTokenProvider.naver(access: "a", refresh: ""), session: session)
        await flow.signInWithNaver(tokenProvider: FakeTokenProvider.naver(access: "", refresh: "r"), session: session)

        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(store.loadToken())
        XCTAssertNotEqual(session.statusMessage, UserSession.signInPrompt)
    }

    func test_apple_truncatesFullNameTo100Characters() async throws {
        StubURLProtocol.setStub(status: 200, json: response("apple:3", token: "jwt-apple"))
        let longName = String(repeating: "가", count: 150)

        await flow.signInWithApple(credential: AppleCredential(identityToken: "t", fullName: longName), session: session)

        let (_, json) = try lastRequestJSON()
        XCTAssertEqual((json["fullName"] as? String)?.count, 100)
    }

    func test_apple_authorizationFailure_makesNoRequest() async {
        StubURLProtocol.setStub(status: 200, json: response("apple:3", token: "x"))

        await flow.signInWithApple(result: .failure(ASAuthorizationError(.canceled)), session: session)

        XCTAssertTrue(StubURLProtocol.requests.isEmpty)
        XCTAssertEqual(session.statusMessage, UserSession.signInPrompt)
    }

    func test_serverReturns401_showsServerMessage_forEachProvider() async {
        StubURLProtocol.setStub(status: 401, json: #"{"message":"유효하지 않은 토큰"}"#)

        await flow.signInWithKakao(tokenProvider: FakeTokenProvider.access("k"), session: session)
        XCTAssertTrue(session.statusMessage.contains("유효하지 않은 토큰"))
        XCTAssertTrue(session.statusMessage.contains("Kakao"))

        await flow.signInWithApple(credential: AppleCredential(identityToken: "t", fullName: nil), session: session)
        XCTAssertTrue(session.statusMessage.contains("유효하지 않은 토큰"))
        XCTAssertTrue(session.statusMessage.contains("Apple"))
        XCTAssertFalse(session.isSignedIn)
    }

    func test_restore_clearsPreviousVersionSocialSessionsWithoutToken_withReloginNotice() {
        for provider in ["apple", "kakao", "naver"] {
            defaults.set(provider, forKey: "auth.provider")
            defaults.set("\(provider):legacy", forKey: "auth.userId")

            let restored = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore())

            XCTAssertFalse(restored.isSignedIn, provider)
            XCTAssertEqual(restored.reloginNotice, UserSession.reloginMessage, provider)
            XCTAssertNil(defaults.string(forKey: "auth.provider"), provider)
        }
    }
}
