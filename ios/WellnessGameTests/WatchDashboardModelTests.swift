import XCTest

// MARK: - Test doubles

/// 요청마다 정해 둔 동작을 실행하는 가짜 조회기. 호출된 자격 증명을 기록한다.
final class FakeSnapshotFetcher: WatchSnapshotFetching, @unchecked Sendable {
    typealias Handler = (WatchAuthContext) async throws -> CharacterSnapshot?

    private let lock = NSLock()
    private var handlers: [Handler]
    private var _requested: [WatchAuthContext] = []

    /// 호출 순서대로 handler를 쓴다. 다 쓰면 마지막 handler를 반복한다.
    init(_ handlers: [Handler]) {
        self.handlers = handlers
    }

    var requested: [WatchAuthContext] {
        lock.withLock { _requested }
    }

    func fetchSnapshot(credentials: WatchAuthContext, now: Date) async throws -> CharacterSnapshot? {
        let handler = lock.withLock { () -> Handler in
            _requested.append(credentials)
            return handlers.count > 1 ? handlers.removeFirst() : handlers[0]
        }
        return try await handler(credentials)
    }
}

private enum ModelFixture {
    static let now = Date(timeIntervalSince1970: 1_790_000_000)

    static func context(token: String = "jwt-a", userId: String = "google:1") -> WatchAuthContext {
        WatchAuthContext(accessToken: token, userId: userId, displayName: nil, expiresAt: nil, language: "ko")
    }

    static func snapshot(level: Int = 3, syncedAt: Date = now) -> CharacterSnapshot {
        CharacterSnapshot(
            level: level,
            currentXp: 10,
            nextLevelXp: 100,
            quests: DailyQuestProgress(steps: 1_000, workoutMinutes: 5, sleepMinutes: 300),
            syncedAt: syncedAt
        )
    }
}

// MARK: - WatchDashboardModel

@MainActor
final class WatchDashboardModelTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!
    private var cache: SnapshotStore!
    private var widgetReloads = 0

    override func setUp() {
        super.setUp()
        suiteName = "WatchDashboardModelTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        cache = SnapshotStore(defaults: defaults)
        widgetReloads = 0
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        cache = nil
        super.tearDown()
    }

    private func makeModel(store: WatchCredentialStore, fetcher: FakeSnapshotFetcher) -> WatchDashboardModel {
        WatchDashboardModel(
            store: store,
            client: fetcher,
            cache: cache,
            reloadWidgets: { [unowned self] in widgetReloads += 1 },
            now: { ModelFixture.now }
        )
    }

    func test_receiveAndRefresh_savesTokenAndCachesSnapshot() async {
        let store = InMemoryWatchCredentialStore()
        let fetcher = FakeSnapshotFetcher([{ _ in ModelFixture.snapshot() }])
        let model = makeModel(store: store, fetcher: fetcher)

        XCTAssertTrue(model.receive(ModelFixture.context().applicationContext))
        await model.refresh()

        XCTAssertEqual(store.load(), ModelFixture.context())
        XCTAssertEqual(model.snapshot, ModelFixture.snapshot())
        XCTAssertEqual(cache.load(), ModelFixture.snapshot())
        XCTAssertEqual(widgetReloads, 1)
    }

    func test_accountSwitch_clearsPreviousSnapshotAndCache() async {
        let store = InMemoryWatchCredentialStore(ModelFixture.context(userId: "google:1"))
        cache.save(ModelFixture.snapshot(level: 9))
        let model = makeModel(store: store, fetcher: FakeSnapshotFetcher([{ _ in nil }]))
        XCTAssertEqual(model.snapshot?.level, 9)

        XCTAssertTrue(model.receive(ModelFixture.context(token: "jwt-b", userId: "kakao:2").applicationContext))

        XCTAssertNil(model.snapshot)
        XCTAssertNil(cache.load())
        XCTAssertEqual(widgetReloads, 1)
    }

    func test_signedOutContext_deletesTokenClearsCacheAndReloadsWidgets() {
        let store = InMemoryWatchCredentialStore(ModelFixture.context())
        cache.save(ModelFixture.snapshot())
        let model = makeModel(store: store, fetcher: FakeSnapshotFetcher([{ _ in nil }]))

        XCTAssertFalse(model.receive(WatchAuthContext.signedOutContext(language: "ko")))

        XCTAssertFalse(model.isSignedIn)
        XCTAssertNil(store.load())
        XCTAssertNil(cache.load())
        XCTAssertEqual(widgetReloads, 1)
    }

    func test_unauthorized_signsOutAndReloadsWidgets() async {
        let store = InMemoryWatchCredentialStore(ModelFixture.context())
        cache.save(ModelFixture.snapshot())
        let model = makeModel(store: store, fetcher: FakeSnapshotFetcher([{ _ in throw WatchAPIError.unauthorized }]))

        await model.refresh()

        XCTAssertFalse(model.isSignedIn)
        XCTAssertNil(model.snapshot)
        XCTAssertNil(store.load())
        XCTAssertNil(cache.load())
        XCTAssertEqual(widgetReloads, 1)
    }

    func test_sameContextAfterUnauthorized_isAppliedAgain() async {
        let store = InMemoryWatchCredentialStore()
        let fetcher = FakeSnapshotFetcher([
            { _ in throw WatchAPIError.unauthorized },
            { _ in ModelFixture.snapshot() },
        ])
        let model = makeModel(store: store, fetcher: fetcher)
        let context = ModelFixture.context().applicationContext

        XCTAssertTrue(model.receive(context))
        await model.refresh()
        XCTAssertFalse(model.isSignedIn)

        // 같은 값이 다시 와도 Keychain이 비어 있으므로 복구해야 한다.
        XCTAssertTrue(model.receive(context))
        await model.refresh()

        XCTAssertTrue(model.isSignedIn)
        XCTAssertEqual(store.load(), ModelFixture.context())
        XCTAssertEqual(model.snapshot, ModelFixture.snapshot())
    }

    func test_duplicateContext_isIgnoredWhenKeychainMatches() {
        let store = InMemoryWatchCredentialStore(ModelFixture.context())
        let model = makeModel(store: store, fetcher: FakeSnapshotFetcher([{ _ in nil }]))

        XCTAssertFalse(model.receive(ModelFixture.context().applicationContext))
    }

    func test_tokenReplacedDuringFetch_refetchesWithNewToken() async {
        let store = InMemoryWatchCredentialStore(ModelFixture.context(token: "jwt-old"))
        let replacement = ModelFixture.context(token: "jwt-new")
        var model: WatchDashboardModel!
        let fetcher = FakeSnapshotFetcher([
            { _ in
                // 첫 조회가 끝나기 전에 iPhone이 새 토큰을 보낸다.
                await MainActor.run { _ = model.receive(replacement.applicationContext) }
                return ModelFixture.snapshot(level: 1)
            },
            { _ in ModelFixture.snapshot(level: 5) },
        ])
        model = makeModel(store: store, fetcher: fetcher)

        await model.refresh()

        XCTAssertEqual(fetcher.requested.map(\.accessToken), ["jwt-old", "jwt-new"])
        XCTAssertEqual(model.snapshot?.level, 5, "이전 토큰의 결과는 버려야 합니다")
        XCTAssertEqual(cache.load()?.level, 5)
    }

    func test_keychainSaveFailure_keepsCredentialsInMemory() async {
        let store = InMemoryWatchCredentialStore()
        store.saveError = KeychainError.unexpectedStatus(-25308)
        let fetcher = FakeSnapshotFetcher([{ _ in ModelFixture.snapshot() }])
        let model = makeModel(store: store, fetcher: fetcher)

        XCTAssertTrue(model.receive(ModelFixture.context().applicationContext))
        await model.refresh()

        XCTAssertNil(store.load())
        XCTAssertTrue(model.isSignedIn)
        XCTAssertEqual(fetcher.requested.map(\.accessToken), ["jwt-a"])
        XCTAssertEqual(model.snapshot, ModelFixture.snapshot())
    }

    func test_staleSnapshot_isReportedAfterTwoHours() {
        let store = InMemoryWatchCredentialStore(ModelFixture.context())
        cache.save(ModelFixture.snapshot(syncedAt: ModelFixture.now.addingTimeInterval(-2 * 60 * 60 - 1)))
        let model = makeModel(store: store, fetcher: FakeSnapshotFetcher([{ _ in nil }]))

        XCTAssertTrue(model.isSnapshotStale())
    }
}

// MARK: - 컴플리케이션 타임라인

final class LevelProviderTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "LevelProviderTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func test_entry_withoutCache_hasNoSnapshot() {
        let provider = LevelProvider(store: SnapshotStore(defaults: defaults))

        let entry = provider.entry(at: ModelFixture.now)

        XCTAssertNil(entry.snapshot)
        XCTAssertFalse(entry.isStale)
    }

    func test_entry_withFreshCache_showsSnapshot() {
        let store = SnapshotStore(defaults: defaults)
        store.save(ModelFixture.snapshot(syncedAt: ModelFixture.now.addingTimeInterval(-60 * 60)))

        let entry = LevelProvider(store: store).entry(at: ModelFixture.now)

        XCTAssertEqual(entry.snapshot?.level, 3)
        XCTAssertFalse(entry.isStale)
    }

    func test_entry_withOldCache_isStale() {
        let store = SnapshotStore(defaults: defaults)
        store.save(ModelFixture.snapshot(syncedAt: ModelFixture.now.addingTimeInterval(-3 * 60 * 60)))

        XCTAssertTrue(LevelProvider(store: store).entry(at: ModelFixture.now).isStale)
    }

    func test_timeline_reloadsAfterThirtyMinutes() {
        let timeline = LevelProvider(store: SnapshotStore(defaults: defaults)).timeline(at: ModelFixture.now)

        XCTAssertEqual(timeline.entries.map(\.date), [ModelFixture.now])
        XCTAssertEqual(LevelProvider.nextRefresh(after: ModelFixture.now), ModelFixture.now.addingTimeInterval(30 * 60))
    }
}
