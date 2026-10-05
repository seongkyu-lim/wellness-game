import XCTest

// Watch 앱의 플랫폼 독립 로직(Watch Core·Shared)을 iOS 테스트 타깃에서 검증한다.

// MARK: - Test doubles

final class InMemoryWatchCredentialStore: WatchCredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: WatchAuthContext?
    var saveError: Error?

    init(_ context: WatchAuthContext? = nil) {
        stored = context
    }

    func load() -> WatchAuthContext? {
        lock.withLock { stored }
    }

    func save(_ context: WatchAuthContext) throws {
        if let saveError {
            throw saveError
        }
        lock.withLock { stored = context }
    }

    func delete() {
        lock.withLock { stored = nil }
    }
}

private enum WatchFixture {
    static let characterPath = "/api/characters/me"
    static let activitiesPath = "/api/health-activities"

    static let character = """
    {"userId":"google:123","character":{"level":4,"currentXp":30,"totalXp":330,"nextLevelXp":120,
     "stats":{"str":2,"vit":3,"intStat":1,"discipline":2,"recovery":1}}}
    """

    static let activities = """
    {"userId":"google:123","date":"2026-10-05","activities":[
     {"type":"SLEEP","source":"APPLE_HEALTH","durationMinutes":null,"calories":null,"distanceMeters":null,
      "steps":null,"sleepMinutes":450,"sleepScore":82,"gainedXp":80,"startedAt":null,"endedAt":null},
     {"type":"STEPS","source":"APPLE_HEALTH","durationMinutes":null,"calories":null,"distanceMeters":null,
      "steps":6400,"sleepMinutes":null,"sleepScore":null,"gainedXp":30,"startedAt":null,"endedAt":null},
     {"type":"WORKOUT","source":"APPLE_HEALTH","durationMinutes":20,"calories":180.5,"distanceMeters":2500.0,
      "steps":null,"sleepMinutes":null,"sleepScore":null,"gainedXp":95,
      "startedAt":"2026-10-05T07:00:00Z","endedAt":"2026-10-05T07:20:00Z"},
     {"type":"WORKOUT","source":"APPLE_HEALTH","durationMinutes":15,"calories":null,"distanceMeters":null,
      "steps":null,"sleepMinutes":null,"sleepScore":null,"gainedXp":45,"startedAt":null,"endedAt":null}
    ]}
    """

    static let emptyActivities = #"{"userId":"google:123","date":"2026-10-05","activities":[]}"#

    static let unauthorized = #"{"status":401,"error":"Unauthorized","message":"토큰이 유효하지 않습니다."}"#

    static let credentials = WatchAuthContext(
        accessToken: "jwt-watch",
        userId: "google:123",
        displayName: "Alice",
        expiresAt: nil,
        language: "ko"
    )
}

// MARK: - Watch 토큰 저장소

final class WatchCredentialStoreTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    func test_apply_savesReceivedContext() throws {
        let store = InMemoryWatchCredentialStore()

        let applied = store.apply(applicationContext: WatchFixture.credentials.applicationContext, now: now)

        XCTAssertEqual(applied, WatchFixture.credentials)
        XCTAssertEqual(store.load(), WatchFixture.credentials)
    }

    func test_apply_emptyContext_deletesStoredCredentials() {
        let store = InMemoryWatchCredentialStore(WatchFixture.credentials)

        let applied = store.apply(applicationContext: WatchAuthContext.signedOutContext(language: "ko"), now: now)

        XCTAssertNil(applied)
        XCTAssertNil(store.load())
    }

    func test_apply_expiredContext_deletesStoredCredentials() {
        let store = InMemoryWatchCredentialStore(WatchFixture.credentials)
        let expired = WatchAuthContext(accessToken: "old", userId: "u", displayName: nil, expiresAt: now, language: "en")

        XCTAssertNil(store.apply(applicationContext: expired.applicationContext, now: now))
        XCTAssertNil(store.load())
    }

    func test_apply_returnsContext_evenWhenKeychainSaveFails() {
        let store = InMemoryWatchCredentialStore()
        store.saveError = KeychainError.unexpectedStatus(-25300)

        XCTAssertEqual(store.apply(applicationContext: WatchFixture.credentials.applicationContext, now: now), WatchFixture.credentials)
    }

    func test_currentCredentials_dropsExpiredToken() {
        let expiring = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: now, language: "en")
        let store = InMemoryWatchCredentialStore(expiring)

        XCTAssertEqual(store.currentCredentials(now: now.addingTimeInterval(-60)), expiring)
        XCTAssertNil(store.currentCredentials(now: now))
        XCTAssertNil(store.load(), "만료된 토큰은 지워야 합니다")
    }

    func test_keychainStore_roundTripsJSON() throws {
        // 테스트마다 다른 service를 써서 실제 Keychain 항목이 겹치지 않게 한다.
        let keychain = KeychainTokenStore(service: "WatchCredentialStoreTests.\(UUID().uuidString)", account: "watchSession")
        let store = KeychainWatchCredentialStore(keychain: keychain)
        defer { store.delete() }

        do {
            try store.save(WatchFixture.credentials)
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("이 테스트 러너에는 Keychain 엔타이틀먼트가 없습니다.")
        }
        XCTAssertEqual(store.load(), WatchFixture.credentials)

        store.delete()
        XCTAssertNil(store.load())
    }
}

// MARK: - Watch 네트워크

final class WatchAPIClientTests: XCTestCase {
    private func makeClient() -> WatchAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return WatchAPIClient(baseURL: URL(string: "http://stub.local")!, session: URLSession(configuration: configuration))
    }

    private let now = ISO8601DateFormatter().date(from: "2026-10-05T12:00:00Z")!
    private let seoul = TimeZone(identifier: "Asia/Seoul")!

    func test_requests_sendBearerTokenAndAcceptLanguage() async throws {
        StubURLProtocol.setStubs([
            WatchFixture.characterPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.character),
            WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.emptyActivities),
        ])

        _ = try await makeClient().fetchSnapshot(credentials: WatchFixture.credentials, now: now, timeZone: seoul)

        XCTAssertEqual(StubURLProtocol.requests.count, 2)
        for request in StubURLProtocol.requests {
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer jwt-watch")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept-Language"), "ko")
        }
    }

    func test_dailyActivities_queriesLocalDate() async throws {
        StubURLProtocol.setStubs([WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.emptyActivities)])
        // UTC 2026-10-05 20:00은 서울에서 10월 6일이다.
        let lateEvening = ISO8601DateFormatter().date(from: "2026-10-05T20:00:00Z")!

        _ = try await makeClient().fetchDailyActivities(credentials: WatchFixture.credentials, date: lateEvening, timeZone: seoul)

        let request = try XCTUnwrap(StubURLProtocol.request(forPath: WatchFixture.activitiesPath))
        let query = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query, [URLQueryItem(name: "date", value: "2026-10-06")])
    }

    func test_dayString_usesGregorianDigitsRegardlessOfLocale() {
        let date = ISO8601DateFormatter().date(from: "2026-01-02T03:00:00Z")!
        XCTAssertEqual(WatchAPIClient.dayString(for: date, timeZone: TimeZone(identifier: "UTC")!), "2026-01-02")
    }

    func test_acceptLanguage_fallsBackForUnsupportedStoredLanguage() async throws {
        StubURLProtocol.setStubs([WatchFixture.characterPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.character)])
        let credentials = WatchAuthContext(accessToken: "t", userId: "u", displayName: nil, expiresAt: nil, language: "en-GB")

        _ = try await makeClient().fetchCharacter(credentials: credentials)

        XCTAssertEqual(StubURLProtocol.requests.last?.value(forHTTPHeaderField: "Accept-Language"), "en")
    }

    func test_unauthorized_throwsUnauthorized() async {
        StubURLProtocol.setStubs([
            WatchFixture.characterPath: StubURLProtocol.Stub(status: 401, json: WatchFixture.unauthorized),
            WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 401, json: WatchFixture.unauthorized),
        ])

        do {
            _ = try await makeClient().fetchSnapshot(credentials: WatchFixture.credentials, now: now, timeZone: seoul)
            XCTFail("401이면 unauthorized를 던져야 합니다")
        } catch {
            XCTAssertEqual(error as? WatchAPIError, .unauthorized)
        }
    }

    func test_serverError_throwsServerStatus() async {
        StubURLProtocol.setStubs([WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 500, json: "{}")])

        do {
            _ = try await makeClient().fetchDailyActivities(credentials: WatchFixture.credentials, date: now, timeZone: seoul)
            XCTFail("500이면 server 오류를 던져야 합니다")
        } catch {
            XCTAssertEqual(error as? WatchAPIError, .server(status: 500))
        }
    }

    func test_missingCharacter_returnsNilSnapshot() async throws {
        StubURLProtocol.setStubs([WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.emptyActivities)])

        let snapshot = try await makeClient().fetchSnapshot(credentials: WatchFixture.credentials, now: now, timeZone: seoul)

        XCTAssertNil(snapshot)
    }

    func test_fetchSnapshot_combinesCharacterAndTodaysQuests() async throws {
        StubURLProtocol.setStubs([
            WatchFixture.characterPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.character),
            WatchFixture.activitiesPath: StubURLProtocol.Stub(status: 200, json: WatchFixture.activities),
        ])

        let snapshot = try await makeClient().fetchSnapshot(credentials: WatchFixture.credentials, now: now, timeZone: seoul)

        XCTAssertEqual(snapshot, CharacterSnapshot(
            level: 4,
            currentXp: 30,
            nextLevelXp: 120,
            quests: DailyQuestProgress(steps: 6_400, workoutMinutes: 35, sleepMinutes: 450),
            syncedAt: now
        ))
        XCTAssertEqual(snapshot?.xpProgress ?? 0, 0.25, accuracy: 0.0001)
        XCTAssertEqual(snapshot?.stage, .sprout)
    }
}

// MARK: - 스냅샷 캐시

final class SnapshotStoreTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "SnapshotStoreTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private let snapshot = CharacterSnapshot(
        level: 7,
        currentXp: 45,
        nextLevelXp: 150,
        quests: DailyQuestProgress(steps: 8_100, workoutMinutes: 30, sleepMinutes: 400),
        syncedAt: Date(timeIntervalSince1970: 1_790_000_000)
    )

    func test_encode_writesStableISO8601JSON() throws {
        let json = try XCTUnwrap(String(data: SnapshotStore.encode(snapshot), encoding: .utf8))

        XCTAssertEqual(json, #"{"currentXp":45,"level":7,"nextLevelXp":150,"quests":{"sleepMinutes":400,"steps":8100,"workoutMinutes":30},"syncedAt":"2026-09-21T14:13:20Z"}"#)
    }

    func test_encodeDecode_roundTrips() throws {
        XCTAssertEqual(try SnapshotStore.decode(SnapshotStore.encode(snapshot)), snapshot)
    }

    func test_saveLoadClear_useInjectedDefaults() {
        let store = SnapshotStore(defaults: defaults)
        XCTAssertNil(store.load())

        store.save(snapshot)
        XCTAssertEqual(store.load(), snapshot)
        XCTAssertNotNil(defaults.data(forKey: SnapshotStore.key))

        store.clear()
        XCTAssertNil(store.load())
    }

    func test_load_ignoresCorruptData() {
        defaults.set(Data("not json".utf8), forKey: SnapshotStore.key)
        XCTAssertNil(SnapshotStore(defaults: defaults).load())
    }

    func test_appGroupIdentifier_matchesEntitlements() {
        XCTAssertEqual(SnapshotStore.appGroupIdentifier, "group.com.example.WellnessGame")
    }
}

// MARK: - 오늘의 퀘스트

final class DailyQuestProgressTests: XCTestCase {
    func test_goals_matchServerXpCalculator() {
        XCTAssertEqual(DailyQuestProgress.stepsGoal, 8_000)
        XCTAssertEqual(DailyQuestProgress.workoutGoalMinutes, 30)
        XCTAssertEqual(DailyQuestProgress.sleepGoalMinutes, 420)
    }

    func test_emptyActivities_areZero() {
        XCTAssertEqual(DailyQuestProgress(activities: []), DailyQuestProgress(steps: 0, workoutMinutes: 0, sleepMinutes: 0))
    }

    func test_questProgress_usesSharedFraction() {
        let quests = DailyQuestProgress(steps: 4_000, workoutMinutes: 45, sleepMinutes: -1)
        XCTAssertEqual(quests.stepsProgress, 0.5)
        XCTAssertEqual(quests.workoutProgress, 1, "목표를 넘으면 1로 자른다")
        XCTAssertEqual(quests.sleepProgress, 0)
    }

    func test_levelProgress_clampsAndHandlesZeroTotal() {
        XCTAssertEqual(LevelProgress.fraction(25, of: 100), 0.25)
        XCTAssertEqual(LevelProgress.fraction(150, of: 100), 1)
        XCTAssertEqual(LevelProgress.fraction(-5, of: 100), 0)
        XCTAssertEqual(LevelProgress.fraction(10, of: 0), 0)
    }
}
