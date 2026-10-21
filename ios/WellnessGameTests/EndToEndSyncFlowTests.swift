import XCTest

// MARK: - 가짜 서버

/// 토큰 발급·회전·폐기와 XP 누적을 흉내 내는 서버. `RoutedURLProtocol`의 핸들러로 쓴다.
///
/// - 로그인·리프레시마다 `access-N`·`refresh-N`을 새로 발급한다. 리프레시 토큰은 한 번만 쓸 수 있다(회전).
/// - 동기화 한 번에 40XP를 더하고 100XP마다 레벨이 오른다.
final class FakeWellnessServer: @unchecked Sendable {
    private let lock = NSLock()
    private var issued = 0
    private var watchIssued = 0
    private var validAccess: Set<String> = []
    private var currentRefresh: String?
    private var totalXp = 0
    private var _syncedDates: [String] = []
    private var _loggedOutRefreshTokens: [String] = []
    private var _rejectRefresh = false

    var syncedDates: [String] { lock.withLock { _syncedDates } }
    var loggedOutRefreshTokens: [String] { lock.withLock { _loggedOutRefreshTokens } }

    /// 리프레시 토큰을 모두 거절한다(재사용 감지·서버 측 폐기 흉내).
    var rejectRefresh: Bool {
        get { lock.withLock { _rejectRefresh } }
        set { lock.withLock { _rejectRefresh = newValue } }
    }

    /// 서버 쪽에서 발급한 액세스 토큰을 모두 무효로 만든다(기기 시계와 무관한 만료).
    func expireAccessTokens() {
        lock.withLock { validAccess.removeAll() }
    }

    func handle(_ request: URLRequest) -> RoutedURLProtocol.Reply {
        let body = RoutedURLProtocol.json(of: request) ?? [:]
        let bearer = BackendFixture.bearer(request)?.replacingOccurrences(of: "Bearer ", with: "")
        return lock.withLock {
            switch request.url?.path {
            case BackendFixture.loginPath:
                let (access, refresh) = issueTokens()
                return .http(status: 200, json: BackendFixture.login(access: access, refresh: refresh))
            case BackendFixture.refreshPath:
                guard !_rejectRefresh, let token = body["refreshToken"] as? String, token == currentRefresh else {
                    return .http(status: 401, json: BackendFixture.error(401, "리프레시 토큰이 유효하지 않습니다."))
                }
                let (access, refresh) = issueTokens()
                return .http(status: 200, json: BackendFixture.refreshed(access: access, refresh: refresh))
            case BackendFixture.syncPath:
                guard let bearer, validAccess.contains(bearer) else {
                    return .http(status: 401, json: BackendFixture.error(401, "토큰이 유효하지 않습니다."))
                }
                let date = body["date"] as? String ?? ""
                _syncedDates.append(date)
                let previousLevel = totalXp / 100 + 1
                totalXp += 40
                let level = totalXp / 100 + 1
                return .http(status: 200, json: BackendFixture.syncResponse(
                    date: date, level: level, currentXp: totalXp % 100, totalXp: totalXp,
                    nextLevelXp: 100, gainedXp: 40, levelUp: level > previousLevel
                ))
            case BackendFixture.watchTokenPath:
                guard let bearer, validAccess.contains(bearer) else {
                    return .http(status: 401, json: BackendFixture.error(401, "토큰이 유효하지 않습니다."))
                }
                watchIssued += 1
                return .http(status: 200, json: BackendFixture.watchToken("watch-\(watchIssued)"))
            case BackendFixture.logoutPath:
                if let token = body["refreshToken"] as? String {
                    _loggedOutRefreshTokens.append(token)
                    if token == currentRefresh {
                        currentRefresh = nil
                    }
                }
                return .http(status: 204, json: "")
            default:
                return .http(status: 404, json: "")
            }
        }
    }

    private func issueTokens() -> (String, String) {
        issued += 1
        let access = "access-\(issued)"
        validAccess.insert(access)
        currentRefresh = "refresh-\(issued)"
        return (access, "refresh-\(issued)")
    }
}

// MARK: - 전체 흐름

/// 로그인 → (전날·)오늘 동기화 → 캐릭터 갱신 → 액세스 만료 → 리프레시 회전 → 로그아웃을
/// 실제 `NetworkClient`·`UserSession`·`HealthSyncService`·`DashboardViewModel` 조합으로 확인한다.
/// 네트워크는 `RoutedURLProtocol` + `FakeWellnessServer`, 시간은 `TestClock`으로 고정한다.
@MainActor
final class EndToEndSyncFlowTests: XCTestCase {
    private var defaults: UserDefaults!
    private var credentials: SessionCredentialStore!
    private var clock: TestClock!
    private var server: FakeWellnessServer!
    private var spy: SpyWatchPublisher!
    private var session: UserSession!
    private var client: NetworkClient!
    private var provider: ClockedHealthProvider!
    private var service: HealthSyncService!

    override func setUp() async throws {
        defaults = ScratchDefaults.make(for: self)
        credentials = SessionCredentialStore(accessStore: InMemoryTokenStore(), refreshStore: InMemoryTokenStore(), defaults: defaults)
        clock = TestClock(kst("2026-10-21 09:00"))
        server = FakeWellnessServer()
        RoutedURLProtocol.route(server.handle)
        spy = SpyWatchPublisher()
        let clock = clock!
        session = UserSession(
            defaults: defaults, credentials: credentials, watchPublisher: spy,
            watchTokenRetryDelay: 3600, now: { clock.now }
        )
        client = NetworkClient(
            baseURL: URL(string: "https://stub.local")!,
            session: RoutedURLProtocol.makeSession(),
            credentials: credentials,
            now: { clock.now }
        )
        provider = ClockedHealthProvider(clock: clock)
        service = HealthSyncService(
            networkClient: client,
            userSession: session,
            history: HealthSyncHistory(defaults: defaults),
            calendar: seoulCalendar,
            serverCalendar: seoulCalendar,
            now: { clock.now }
        )
        // 마지막 성공이 어젯밤이라 첫 동기화는 전날부터 다시 보낸다.
        HealthSyncHistory(defaults: defaults).recordSuccess(at: kst("2026-10-20 21:00"))
    }

    private func makeViewModel() -> DashboardViewModel {
        DashboardViewModel(
            appleProvider: provider,
            mockProvider: provider,
            networkClient: client,
            userSession: session,
            syncService: service
        )
    }

    private func bearers(_ path: String) -> [String?] {
        RoutedURLProtocol.requests(path: path).map(BackendFixture.bearer)
    }

    private func logIn() async {
        await CredentialAuthService(networkClient: client).logIn(username: "alice", password: "password1", into: session)
    }

    func test_로그인부터_동기화_캐릭터갱신_토큰만료_회전_로그아웃까지() async throws {
        let viewModel = makeViewModel()

        // 1. 로그인: 서버가 준 토큰과 userId로 세션을 만든다.
        await logIn()
        XCTAssertEqual(session.userId, "password:alice")
        XCTAssertEqual(credentials.accessToken, "access-1")
        XCTAssertEqual(credentials.refreshToken, "refresh-1")

        // 2. 첫 자동 동기화: 전날 → 오늘 순서로 보내고 Watch 조회 토큰을 발급해 보낸다.
        await viewModel.autoSync()
        XCTAssertEqual(server.syncedDates, ["2026-10-20", "2026-10-21"])
        XCTAssertEqual(provider.loadedDays, [kst("2026-10-20 00:00")])
        XCTAssertEqual(viewModel.syncResponse?.character.level, 1)
        XCTAssertEqual(viewModel.syncResponse?.character.totalXp, 80)
        XCTAssertEqual(viewModel.statusMessage, String(localized: "자동 동기화 완료"))
        XCTAssertEqual(bearers(BackendFixture.watchTokenPath), ["Bearer access-1"])
        let watch = try XCTUnwrap(spy.lastContext)
        XCTAssertEqual(watch.accessToken, "watch-1")
        XCTAssertEqual(watch.expiresAt, kst("2026-10-28 09:00"))

        // 3. 기기 시계로 액세스 토큰이 만료됨 → 요청 전에 갱신(회전)하고, 레벨업한 캐릭터를 받는다.
        clock.advance(3600)
        await viewModel.autoSync(force: true)
        XCTAssertEqual(
            RoutedURLProtocol.requests(path: BackendFixture.refreshPath).compactMap { RoutedURLProtocol.json(of: $0)?["refreshToken"] as? String },
            ["refresh-1"]
        )
        XCTAssertEqual(bearers(BackendFixture.syncPath).last, "Bearer access-2")
        XCTAssertEqual(server.syncedDates, ["2026-10-20", "2026-10-21", "2026-10-21"], "이미 오늘 성공했으므로 전날은 다시 보내지 않는다")
        XCTAssertEqual(viewModel.syncResponse?.levelUp, true)
        XCTAssertEqual(viewModel.syncResponse?.character.level, 2)
        XCTAssertEqual(viewModel.statusMessage, String(localized: "레벨업! 성장 결과를 확인하세요."))
        XCTAssertEqual(credentials.refreshToken, "refresh-2")
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.watchTokenPath).count, 1, "Watch 토큰은 아직 만료가 멀다")

        // 4. 서버 쪽에서 액세스 토큰이 무효가 됨 → 401 → 리프레시 회전 → 원래 요청을 한 번만 다시 보낸다.
        server.expireAccessTokens()
        clock.advance(60)
        await viewModel.autoSync(force: true)
        XCTAssertEqual(bearers(BackendFixture.syncPath).suffix(2), ["Bearer access-2", "Bearer access-3"])
        XCTAssertEqual(credentials.accessToken, "access-3")
        XCTAssertEqual(credentials.refreshToken, "refresh-3")
        XCTAssertEqual(viewModel.syncResponse?.character.totalXp, 160)
        XCTAssertTrue(session.isSignedIn)

        // 5. 로그아웃: 마지막 리프레시 토큰을 폐기하고 로컬 세션·Watch 정보를 지운다.
        var signedOutProvider: LoginProvider?
        await SocialSignInFlow(networkClient: client, pendingAppleNames: PendingAppleNameStore(backing: InMemoryTokenStore()))
            .signOut(session: session) { signedOutProvider = $0 }
        viewModel.reset()
        XCTAssertEqual(server.loggedOutRefreshTokens, ["refresh-3"])
        XCTAssertNil(RoutedURLProtocol.requests(path: BackendFixture.logoutPath).first.flatMap(BackendFixture.bearer))
        XCTAssertEqual(signedOutProvider, .password)
        XCTAssertFalse(session.isSignedIn)
        XCTAssertNil(session.reloginNotice)
        XCTAssertNil(credentials.accessToken)
        XCTAssertNil(credentials.refreshToken)
        XCTAssertNil(spy.lastContext)
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertEqual(viewModel.statusMessage, UserSession.signInPrompt)

        // 6. 로그아웃 뒤 자동 동기화는 아무 요청도 보내지 않는다.
        let requestCount = RoutedURLProtocol.requests.count
        await viewModel.autoSync(force: true)
        XCTAssertEqual(RoutedURLProtocol.requests.count, requestCount)
    }

    func test_리프레시까지_거절되면_세션을_끝내고_재로그인을_안내한다() async throws {
        let viewModel = makeViewModel()
        await logIn()
        await viewModel.autoSync()
        server.expireAccessTokens()
        server.rejectRefresh = true

        await viewModel.autoSync(force: true)

        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertEqual(viewModel.statusMessage, UserSession.reloginMessage)
        XCTAssertNil(viewModel.syncResponse)
        XCTAssertNil(credentials.refreshToken)
        XCTAssertNil(spy.lastContext, "Watch에도 로그아웃을 알린다")
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).count, 1, "거절된 리프레시는 다시 시도하지 않는다")
    }

    // MARK: 백그라운드

    func test_백그라운드_전달_여러개가_한번의_동기화로_묶이고_만료된_토큰을_회전한_뒤_세션만료도_처리한다() async throws {
        await logIn()
        clock.advance(3600) // 앱이 꺼진 사이 액세스 토큰이 만료됐다.
        let clock = clock!
        let credentials = credentials!
        let service = service!
        let tasks = FakeBackgroundTasks()
        let runner = BackgroundHealthSyncRunner(
            isAppActive: { false },
            isProtectedDataAvailable: { true },
            checkCredentialsReadable: { try credentials.checkReadable() },
            syncService: { service },
            provider: provider,
            backgroundTasks: tasks
        )
        var outcomes: [BackgroundSyncOutcome] = []
        let sleeper = ManualSleeper()
        let coordinator = BackgroundSyncCoordinator(
            now: { clock.now },
            sleep: { await sleeper.sleep($0) },
            isRefreshInFlight: { credentials.refreshCoordinator.isRefreshing },
            perform: { outcomes.append(await runner.run()) }
        )
        let observer = FakeHealthUpdateObserver()
        let background = HealthBackgroundSync(
            observer: observer,
            coordinator: coordinator,
            notificationCenter: NotificationCenter(),
            protectedDataNotification: Notification.Name("EndToEndSyncFlowTests.protectedData")
        )
        defer { sleeper.wake { _ in true } }

        background.start()
        await background.enableDeliveryIfAuthorized()
        XCTAssertTrue(background.isDeliveryEnabled)

        // 걸음·수면 전달이 거의 동시에 온다.
        let delivered = expectation(description: "HealthKit completion")
        delivered.expectedFulfillmentCount = 2
        observer.fire(.steps) { delivered.fulfill() }
        observer.fire(.sleep) { delivered.fulfill() }
        await fulfillment(of: [delivered], timeout: 5)

        XCTAssertEqual(outcomes, [.synced])
        XCTAssertEqual(server.syncedDates, ["2026-10-20", "2026-10-21"])
        XCTAssertEqual(RoutedURLProtocol.requests(path: BackendFixture.refreshPath).count, 1)
        XCTAssertEqual(Set(bearers(BackendFixture.syncPath)), ["Bearer access-2"])
        XCTAssertEqual(credentials.refreshToken, "refresh-2", "회전된 리프레시 토큰을 completion 전에 저장한다")
        XCTAssertEqual(spy.lastContext?.accessToken, "watch-1")
        XCTAssertEqual(HealthSyncHistory(defaults: defaults).lastSuccessAt, clock.now)
        XCTAssertEqual(tasks.begun, 1)
        XCTAssertEqual(tasks.ended, [1])
        XCTAssertFalse(coordinator.isRunning)

        // 최소 간격이 지난 뒤, 서버가 세션을 폐기한 상태에서 운동 전달이 온다.
        server.expireAccessTokens()
        server.rejectRefresh = true
        clock.advance(BackgroundSyncCoordinator.defaultMinimumInterval + 1)
        let ended = expectation(description: "HealthKit completion after session end")
        observer.fire(.workouts) { ended.fulfill() }
        await fulfillment(of: [ended], timeout: 5)

        XCTAssertEqual(outcomes, [.synced, .failed])
        XCTAssertFalse(session.isSignedIn)
        XCTAssertEqual(session.reloginNotice, UserSession.reloginMessage)
        XCTAssertNil(spy.lastContext)
        XCTAssertEqual(tasks.ended, [1, 2], "실패해도 백그라운드 작업을 끝낸다")
        XCTAssertEqual(server.syncedDates.count, 2)
    }
}
