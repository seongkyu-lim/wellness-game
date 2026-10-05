import WatchConnectivity
import XCTest

// MARK: - Test doubles

/// `WCSession` 대신 쓰는 가짜 세션. 보낸 컨텍스트를 기록한다.
final class FakeWatchContextSession: WatchContextSession {
    var activationState: WCSessionActivationState = .activated
    var isPaired = true
    var isWatchAppInstalled = true
    var sendError: Error?
    private(set) var sentContexts: [[String: Any]] = []

    func updateApplicationContext(_ applicationContext: [String: Any]) throws {
        if let sendError {
            throw sendError
        }
        sentContexts.append(applicationContext)
    }
}

/// `UserSession`이 Watch에 보낸 값을 기록한다.
final class SpyWatchPublisher: WatchAuthPublishing {
    private(set) var published: [WatchAuthContext?] = []

    func publish(_ context: WatchAuthContext?) {
        published.append(context)
    }
}

/// 테스트가 실제 WCSession(`WatchAuthRelay.shared`)을 건드리지 않도록 `UserSession`에 주입한다.
final class NoopWatchPublisher: WatchAuthPublishing {
    func publish(_ context: WatchAuthContext?) {}
}

// MARK: - WatchAuthContext

final class WatchAuthContextTests: XCTestCase {
    private let expiresAt = Date(timeIntervalSince1970: 1_800_000_000)

    func test_applicationContext_roundTripsAllFields() throws {
        let context = WatchAuthContext(
            accessToken: "jwt-abc",
            userId: "google:123",
            displayName: "Alice",
            expiresAt: expiresAt,
            language: "ko"
        )

        let dictionary = context.applicationContext
        let decoded = try XCTUnwrap(WatchAuthContext(applicationContext: dictionary))

        XCTAssertEqual(decoded, context)
        XCTAssertEqual(dictionary["accessToken"] as? String, "jwt-abc")
        XCTAssertEqual(dictionary["userId"] as? String, "google:123")
        XCTAssertEqual(dictionary["displayName"] as? String, "Alice")
        XCTAssertEqual(dictionary["expiresAt"] as? Double, 1_800_000_000)
        XCTAssertEqual(dictionary["language"] as? String, "ko")
    }

    func test_applicationContext_isPropertyListCompatible() throws {
        // WCSession은 property list 타입만 전송한다.
        let context = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: nil, language: "en")
        for dictionary in [context.applicationContext, WatchAuthContext.signedOutContext(language: "en")] {
            XCTAssertNoThrow(try PropertyListSerialization.data(fromPropertyList: dictionary, format: .binary, options: 0))
        }
    }

    func test_missingOptionalFields_areEncodedAsEmpty_andDecodedAsNil() throws {
        let context = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: nil, language: "en")

        let dictionary = context.applicationContext
        XCTAssertEqual(dictionary["displayName"] as? String, "")
        XCTAssertEqual(dictionary["expiresAt"] as? Double, 0)

        let decoded = try XCTUnwrap(WatchAuthContext(applicationContext: dictionary))
        XCTAssertNil(decoded.displayName)
        XCTAssertNil(decoded.expiresAt)
    }

    func test_signedOutContext_hasEmptyValues_andDecodesToNil() {
        let dictionary = WatchAuthContext.signedOutContext(language: "ko")

        XCTAssertEqual(dictionary["accessToken"] as? String, "")
        XCTAssertEqual(dictionary["userId"] as? String, "")
        XCTAssertEqual(dictionary["language"] as? String, "ko")
        XCTAssertNil(WatchAuthContext(applicationContext: dictionary))
    }

    func test_decode_rejectsMissingTokenOrUserId() {
        XCTAssertNil(WatchAuthContext(applicationContext: [:]))
        XCTAssertNil(WatchAuthContext(applicationContext: ["accessToken": "t"]))
        XCTAssertNil(WatchAuthContext(applicationContext: ["userId": "u"]))
        XCTAssertNil(WatchAuthContext(applicationContext: ["accessToken": 1, "userId": "u"]))
    }

    func test_decode_normalizesLanguage_andFallsBackToEnglish() throws {
        let korean = try XCTUnwrap(WatchAuthContext(applicationContext: ["accessToken": "t", "userId": "u", "language": "ko-KR"]))
        XCTAssertEqual(korean.language, "ko")

        let unsupported = try XCTUnwrap(WatchAuthContext(applicationContext: ["accessToken": "t", "userId": "u", "language": "ja"]))
        XCTAssertEqual(unsupported.language, "en")

        let missing = try XCTUnwrap(WatchAuthContext(applicationContext: ["accessToken": "t", "userId": "u"]))
        XCTAssertEqual(missing.language, "en")
    }

    func test_decode_acceptsIntegerExpiresAt() throws {
        let decoded = try XCTUnwrap(WatchAuthContext(applicationContext: ["accessToken": "t", "userId": "u", "expiresAt": 1_800_000_000]))
        XCTAssertEqual(decoded.expiresAt, expiresAt)
    }

    func test_isExpired_comparesWithNow() {
        let context = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: expiresAt, language: "en")
        XCTAssertFalse(context.isExpired(at: expiresAt.addingTimeInterval(-1)))
        XCTAssertTrue(context.isExpired(at: expiresAt))

        let noExpiry = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: nil, language: "en")
        XCTAssertFalse(noExpiry.isExpired(at: .distantFuture))
    }
}

// MARK: - WatchAuthRelay (iPhone)

final class WatchAuthRelayTests: XCTestCase {
    private let context = WatchAuthContext(accessToken: "jwt", userId: "u", displayName: nil, expiresAt: nil, language: "ko")

    func test_publish_sendsContext_whenWatchIsReachable() throws {
        let session = FakeWatchContextSession()
        let relay = WatchAuthRelay(session: session)

        relay.publish(context)

        let sent = try XCTUnwrap(session.sentContexts.last)
        XCTAssertEqual(WatchAuthContext(applicationContext: sent), context)
    }

    func test_publishNil_sendsSignedOutContext_withCurrentLanguage() throws {
        let session = FakeWatchContextSession()
        let relay = WatchAuthRelay(session: session, language: { "ko" })

        relay.publish(nil)

        let sent = try XCTUnwrap(session.sentContexts.last)
        XCTAssertEqual(sent["accessToken"] as? String, "")
        XCTAssertEqual(sent["language"] as? String, "ko")
        XCTAssertNil(WatchAuthContext(applicationContext: sent))
    }

    func test_publish_isIgnored_whenSessionUnsupported() {
        let relay = WatchAuthRelay(session: nil)
        relay.publish(context)
        relay.flush()
        // 크래시 없이 조용히 무시되면 된다.
    }

    func test_publish_skipsSending_whenNoPairedWatchOrAppNotInstalled() {
        let unpaired = FakeWatchContextSession()
        unpaired.isPaired = false
        WatchAuthRelay(session: unpaired).publish(context)
        XCTAssertTrue(unpaired.sentContexts.isEmpty)

        let notInstalled = FakeWatchContextSession()
        notInstalled.isWatchAppInstalled = false
        WatchAuthRelay(session: notInstalled).publish(context)
        XCTAssertTrue(notInstalled.sentContexts.isEmpty)
    }

    func test_latestContext_isSentAfterActivationCompletes() throws {
        let session = FakeWatchContextSession()
        session.activationState = .notActivated
        let relay = WatchAuthRelay(session: session)

        relay.publish(nil)
        relay.publish(context)
        XCTAssertTrue(session.sentContexts.isEmpty, "활성화 전에는 보내지 않아야 합니다")

        session.activationState = .activated
        relay.flush()

        XCTAssertEqual(session.sentContexts.count, 1)
        XCTAssertEqual(WatchAuthContext(applicationContext: try XCTUnwrap(session.sentContexts.first)), context)
    }

    func test_localeChange_resendsLatestContextWithNewLanguage() throws {
        let session = FakeWatchContextSession()
        var language = "ko"
        let center = NotificationCenter()
        let relay = WatchAuthRelay(session: session, language: { language }, notificationCenter: center)
        relay.publish(context)

        language = "en"
        center.post(name: NSLocale.currentLocaleDidChangeNotification, object: nil)

        XCTAssertEqual(session.sentContexts.count, 2)
        let resent = try XCTUnwrap(WatchAuthContext(applicationContext: try XCTUnwrap(session.sentContexts.last)))
        XCTAssertEqual(resent.language, "en")
        XCTAssertEqual(resent.accessToken, context.accessToken)
    }

    func test_localeChange_withoutLanguageChange_doesNotResend() {
        let session = FakeWatchContextSession()
        let center = NotificationCenter()
        let relay = WatchAuthRelay(session: session, language: { "ko" }, notificationCenter: center)
        relay.publish(context)

        center.post(name: NSLocale.currentLocaleDidChangeNotification, object: nil)

        XCTAssertEqual(session.sentContexts.count, 1)
    }

    func test_sendFailure_isSwallowed() {
        let session = FakeWatchContextSession()
        session.sendError = NSError(domain: WCErrorDomain, code: WCError.Code.sessionNotActivated.rawValue)
        let relay = WatchAuthRelay(session: session)

        relay.publish(context)

        XCTAssertTrue(session.sentContexts.isEmpty)
    }
}

// MARK: - UserSession → Watch

@MainActor
final class UserSessionWatchSyncTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "UserSessionWatchSyncTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private let auth = AuthTokenResponse(
        userId: "google:123",
        displayName: "Alice",
        accessToken: "jwt-google",
        tokenType: "Bearer",
        expiresIn: 3600,
        userIdentifier: nil
    )

    func test_signIn_publishesTokenUserIdAndExpiry() throws {
        let spy = SpyWatchPublisher()
        let issuedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: spy, now: { issuedAt })

        try session.signIn(provider: .google, auth: auth)

        let sent = try XCTUnwrap(spy.published.last ?? nil)
        XCTAssertEqual(sent.accessToken, "jwt-google")
        XCTAssertEqual(sent.userId, "google:123")
        XCTAssertEqual(sent.displayName, "Alice")
        XCTAssertEqual(sent.expiresAt, issuedAt.addingTimeInterval(3600))
        XCTAssertTrue(["ko", "en"].contains(sent.language))
    }

    func test_signOut_andUnauthorized_publishEmptyContext() throws {
        let spy = SpyWatchPublisher()
        let session = UserSession(defaults: defaults, tokenStore: InMemoryTokenStore(), watchPublisher: spy)

        try session.signIn(provider: .google, auth: auth)
        session.signOut()
        XCTAssertEqual(spy.published.count, 3, "복원(빈 값) → 로그인 → 로그아웃 순서로 보내야 합니다")
        XCTAssertNil(spy.published.last ?? nil)

        try session.signIn(provider: .google, auth: auth)
        session.handleUnauthorized()
        XCTAssertNil(spy.published.last ?? nil)
    }

    func test_restoredSession_isPublishedOnLaunch() throws {
        let store = InMemoryTokenStore()
        try UserSession(defaults: defaults, tokenStore: store, watchPublisher: SpyWatchPublisher())
            .signIn(provider: .google, auth: auth)

        let spy = SpyWatchPublisher()
        _ = UserSession(defaults: defaults, tokenStore: store, watchPublisher: spy)

        XCTAssertEqual(spy.published.count, 1)
        XCTAssertEqual((spy.published.first ?? nil)?.accessToken, "jwt-google")
    }
}
