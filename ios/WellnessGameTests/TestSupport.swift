import WatchConnectivity
import XCTest

// 여러 테스트 파일이 함께 쓰는 테스트 더블·픽스처·시간 도우미.

// MARK: - 네트워크 stub

/// 네트워크 없이 요청을 가로채 응답을 돌려주는 URLProtocol stub.
///
/// - `route`: 요청마다 핸들러로 응답을 정한다(같은 경로라도 Authorization 등에 따라 다르게 답할 수 있다).
/// - `setStub`·`setStubs`: 고정 응답 하나 또는 경로별 응답 맵. 맵에 없는 경로는 `default`(기본 404)를 돌려준다.
/// - 응답은 HTTP 응답·전송 실패·HTTP가 아닌 응답 중 하나이고, `delay`만큼 늦게 보낼 수 있다.
final class RoutedURLProtocol: URLProtocol {
    struct Reply {
        enum Kind {
            case http(status: Int, body: Data)
            case failure(URLError.Code)
            case nonHTTP
        }

        let kind: Kind
        var delay: TimeInterval = 0

        init(status: Int, json: String, delay: TimeInterval = 0) {
            kind = .http(status: status, body: Data(json.utf8))
            self.delay = delay
        }

        private init(kind: Kind) {
            self.kind = kind
        }

        static func http(status: Int, json: String, delay: TimeInterval = 0) -> Reply {
            Reply(status: status, json: json, delay: delay)
        }

        static func failure(_ code: URLError.Code) -> Reply {
            Reply(kind: .failure(code))
        }

        static let nonHTTP = Reply(kind: .nonHTTP)
    }

    private static let lock = NSLock()
    private static var _handler: (URLRequest) -> Reply = { _ in Reply(status: 404, json: "") }
    private static var _requests: [URLRequest] = []

    /// 요청마다 응답을 정하고, 기록한 요청을 비운다.
    static func route(_ handler: @escaping (URLRequest) -> Reply) {
        lock.withLock {
            _handler = handler
            _requests = []
        }
    }

    /// 모든 경로에 같은 응답을 돌려준다.
    static func setStub(status: Int, json: String) {
        let reply = Reply(status: status, json: json)
        route { _ in reply }
    }

    /// 경로(`/api/...`)별 응답. 맵에 없는 경로는 `default`를 돌려준다.
    static func setStubs(_ stubs: [String: Reply], default defaultReply: Reply = Reply(status: 404, json: "")) {
        route { stubs[$0.url?.path ?? ""] ?? defaultReply }
    }

    static var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    static func requests(path: String) -> [URLRequest] {
        requests.filter { $0.url?.path == path }
    }

    static func request(forPath path: String) -> URLRequest? {
        requests.first { $0.url?.path == path }
    }

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RoutedURLProtocol.self]
        return URLSession(configuration: configuration)
    }

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

    static func json(of request: URLRequest) -> [String: Any]? {
        guard let data = bodyData(of: request) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// 문자열 값만 있는 본문(토큰 교환·리프레시 요청 등).
    static func stringFields(of request: URLRequest) -> [String: String]? {
        json(of: request) as? [String: String]
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let reply = Self.lock.withLock { () -> Reply in
            Self._requests.append(request)
            return Self._handler(request)
        }
        if reply.delay > 0 {
            DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay) { [weak self] in
                self?.send(reply.kind)
            }
        } else {
            send(reply.kind)
        }
    }

    private func send(_ kind: Reply.Kind) {
        switch kind {
        case let .http(status, body):
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case let .failure(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .nonHTTP:
            let response = URLResponse(url: request.url!, mimeType: "application/json", expectedContentLength: 2, textEncodingName: nil)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("{}".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

// MARK: - 백엔드 응답 픽스처

/// `Fixtures/*.json`(백엔드 record 하나당 파일 하나, `Fixtures/README.md` 참고)을 읽고,
/// 테스트마다 필요한 값만 바꿔 응답 JSON을 만든다.
enum BackendFixture {
    static let loginPath = "/api/auth/login"
    static let signUpPath = "/api/auth/signup"
    static let syncPath = "/api/health-activities/sync"
    static let refreshPath = "/api/auth/refresh"
    static let logoutPath = "/api/auth/logout"
    static let watchTokenPath = "/api/auth/watch-token"

    private final class BundleToken {}

    /// 픽스처 원문.
    static func data(_ name: String, file: StaticString = #filePath, line: UInt = #line) -> Data {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "json"),
              let data = try? Data(contentsOf: url) else {
            XCTFail("픽스처 \(name).json을 테스트 번들에서 찾지 못했습니다", file: file, line: line)
            return Data()
        }
        return data
    }

    /// 픽스처를 읽어 최상위 값(`overrides`)과 `character` 값(`character`)을 바꾼다. 값이 `NSNull`이면 키를 지운다.
    static func json(_ name: String, overrides: [String: Any] = [:], character: [String: Any] = [:]) -> String {
        guard var object = (try? JSONSerialization.jsonObject(with: data(name))) as? [String: Any] else {
            XCTFail("픽스처 \(name).json이 JSON 객체가 아닙니다")
            return "{}"
        }
        for (key, value) in overrides {
            object[key] = value is NSNull ? nil : value
        }
        if !character.isEmpty, var nested = object["character"] as? [String: Any] {
            nested.merge(character) { _, new in new }
            object["character"] = nested
        }
        let encoded = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        return String(decoding: encoded, as: UTF8.self)
    }

    /// `HealthActivitySyncResponse`.
    static func syncResponse(
        date: String = "2026-10-21",
        level: Int = 1,
        currentXp: Int = 40,
        totalXp: Int = 40,
        nextLevelXp: Int = 100,
        gainedXp: Int = 40,
        levelUp: Bool = false
    ) -> String {
        json(
            "HealthActivitySyncResponse",
            overrides: ["date": date, "gainedXp": gainedXp, "levelUp": levelUp],
            character: ["level": level, "currentXp": currentXp, "totalXp": totalXp, "nextLevelXp": nextLevelXp]
        )
    }

    /// `AuthController.PasswordAuthResponse`. `refresh`가 nil이면 Auth v2 이전 응답(리프레시 필드 없음).
    static func login(access: String, refresh: String?, expiresIn: Int = 3600) -> String {
        json("PasswordAuthResponse", overrides: [
            "accessToken": access,
            "expiresIn": expiresIn,
            "refreshToken": refresh ?? NSNull(),
            "refreshExpiresIn": refresh == nil ? NSNull() : 2_592_000,
        ])
    }

    /// `AuthController.RefreshResponse`.
    static func refreshed(access: String, refresh: String, expiresIn: Int = 3600) -> String {
        json("RefreshResponse", overrides: ["accessToken": access, "refreshToken": refresh, "expiresIn": expiresIn])
    }

    /// `/api/auth/watch-token` 응답(`com.wellnessgame.auth.IssuedToken`).
    static func watchToken(_ token: String) -> String {
        json("IssuedToken", overrides: ["accessToken": token])
    }

    /// `ApiExceptionHandler`의 오류 본문.
    static func error(_ status: Int, _ message: String) -> String {
        json("ErrorResponse", overrides: ["status": status, "message": message])
    }

    static func bearer(_ request: URLRequest) -> String? {
        request.value(forHTTPHeaderField: "Authorization")
    }
}

/// Auth v2(짧은 액세스 토큰 + 회전 리프레시 토큰) 테스트가 쓰는 값. 경로·응답은 `BackendFixture`를 따른다.
enum AuthV2 {
    static let syncPath = BackendFixture.syncPath
    static let refreshPath = BackendFixture.refreshPath
    static let logoutPath = BackendFixture.logoutPath
    static let watchTokenPath = BackendFixture.watchTokenPath

    static let syncResponse = BackendFixture.syncResponse(date: "2026-10-04", currentXp: 10, totalXp: 10, gainedXp: 10)
    static let unauthorized = BackendFixture.error(401, "토큰이 유효하지 않습니다.")

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
        BackendFixture.refreshed(access: access, refresh: refresh)
    }

    static func watchToken(_ token: String) -> String {
        BackendFixture.watchToken(token)
    }

    static func syncRequest() -> HealthActivitySyncRequest {
        HealthActivitySyncRequest(
            userId: "password:alice",
            date: "2026-10-04",
            activities: [HealthActivityDTO(type: .steps, steps: 1000)]
        )
    }

    static func bearer(_ request: URLRequest) -> String? {
        BackendFixture.bearer(request)
    }
}

// MARK: - 저장소

/// 테스트마다 새 UserDefaults 저장소를 만들고, 테스트가 끝나면 지운다.
enum ScratchDefaults {
    static func make(for testCase: XCTestCase) -> UserDefaults {
        let suiteName = "WellnessGameTests.\(type(of: testCase)).\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        testCase.addTeardownBlock {
            defaults.removePersistentDomain(forName: suiteName)
        }
        return defaults
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

/// 다음 저장 한 번을 실패시키는 토큰 저장소(Keychain 저장 실패 흉내).
final class FlakyTokenStore: AccessTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?
    private var _failNextSave = false

    var failNextSave: Bool {
        get { lock.withLock { _failNextSave } }
        set { lock.withLock { _failNextSave = newValue } }
    }

    func loadToken() -> String? { lock.withLock { token } }

    func saveToken(_ token: String) throws {
        try lock.withLock {
            if _failNextSave {
                _failNextSave = false
                throw KeychainError.unexpectedStatus(errSecInteractionNotAllowed)
            }
            self.token = token
        }
    }

    func deleteToken() { lock.withLock { token = nil } }
}

/// 잠금 상태를 흉내 내는 토큰 저장소. 잠겨 있으면 읽지 못하고 `checkReadable()`이 던진다.
final class LockableTokenStore: AccessTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?
    private var _isLocked = false

    init(token: String?) {
        self.token = token
    }

    var isLocked: Bool {
        get { lock.withLock { _isLocked } }
        set { lock.withLock { _isLocked = newValue } }
    }

    var storedToken: String? {
        lock.withLock { token }
    }

    func loadToken() -> String? {
        lock.withLock { _isLocked ? nil : token }
    }

    func saveToken(_ token: String) throws {
        lock.withLock { self.token = token }
    }

    func deleteToken() {
        lock.withLock {
            // 잠겨 있으면 Keychain 삭제도 실패한다.
            if !_isLocked { token = nil }
        }
    }

    func checkReadable() throws {
        if isLocked { throw KeychainError.locked }
    }
}

/// 기존 테스트용: 액세스 토큰 저장소만 주고 리프레시 토큰은 없는(이전 버전) 세션을 만든다.
extension SessionCredentialStore {
    static func legacy(_ accessStore: InMemoryTokenStore, defaults: UserDefaults = .testScratch) -> SessionCredentialStore {
        SessionCredentialStore(accessStore: accessStore, refreshStore: InMemoryTokenStore(), defaults: defaults)
    }
}

extension UserDefaults {
    /// 만료 시각을 검증하지 않는 테스트가 쓰는 공용 저장소.
    static let testScratch = UserDefaults(suiteName: "WellnessGameTests.scratch")!
}

extension UserSession {
    convenience init(
        defaults: UserDefaults,
        tokenStore: InMemoryTokenStore,
        watchPublisher: WatchAuthPublishing,
        now: @escaping () -> Date = Date.init
    ) {
        self.init(
            defaults: defaults,
            credentials: .legacy(tokenStore, defaults: defaults),
            watchPublisher: watchPublisher,
            now: now
        )
    }
}

extension NetworkClient {
    init(baseURL: URL, session: URLSession, tokenStore: InMemoryTokenStore?) {
        self.init(baseURL: baseURL, session: session, credentials: tokenStore.map { .legacy($0) })
    }
}

// MARK: - 시간

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

/// 서버와 같은 한국 시간 달력.
let seoulCalendar: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
    return calendar
}()

/// 한국 시간 `yyyy-MM-dd HH:mm`.
func kst(_ text: String) -> Date {
    let formatter = DateFormatter()
    formatter.calendar = seoulCalendar
    formatter.timeZone = seoulCalendar.timeZone
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd HH:mm"
    return formatter.date(from: text)!
}

/// 시간을 직접 흘려보내는 sleep. 벽시계를 기다리지 않는다.
@MainActor
final class ManualSleeper {
    private var sleepers: [(seconds: TimeInterval, continuation: CheckedContinuation<Void, Never>)] = []

    var pendingDelays: [TimeInterval] {
        sleepers.map(\.seconds)
    }

    nonisolated func sleep(_ seconds: TimeInterval) async {
        await enqueue(seconds)
    }

    private func enqueue(_ seconds: TimeInterval) async {
        await withCheckedContinuation { sleepers.append((seconds, $0)) }
    }

    /// 조건에 맞는 sleep을 깨운다.
    func wake(where matches: (TimeInterval) -> Bool) {
        let woken = sleepers.filter { matches($0.seconds) }
        sleepers.removeAll { matches($0.seconds) }
        woken.forEach { $0.continuation.resume() }
    }
}

// MARK: - 건강 데이터·백그라운드

/// completion 호출 횟수를 센다.
final class CompletionCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var _count = 0

    var count: Int { lock.withLock { _count } }

    func callAsFunction() {
        lock.withLock { _count += 1 }
    }
}

/// 테스트 시계 기준으로 오늘·지난 날 스냅샷을 돌려주는 건강 데이터 제공자.
/// `holdToday`면 `releaseToday()` 전까지 오늘 데이터 읽기를 멈추고, 멈춘 순간 `todayLoadStarted`를 채운다.
@MainActor
final class ClockedHealthProvider: HealthDataProvider {
    private let clock: TestClock
    private(set) var authorizationRequests = 0
    private(set) var loadedDays: [Date] = []
    private(set) var todayLoads = 0
    var holdToday = false
    var todayLoadStarted: XCTestExpectation?
    var steps = 8_500
    private var held: CheckedContinuation<Void, Never>?

    init(clock: TestClock) {
        self.clock = clock
    }

    nonisolated var isAvailable: Bool { true }

    func requestAuthorization() async throws {
        authorizationRequests += 1
    }

    func loadToday() async throws -> DailyHealthSnapshot {
        todayLoads += 1
        if holdToday {
            await withCheckedContinuation {
                held = $0
                todayLoadStarted?.fulfill()
            }
        }
        return DailyHealthSnapshot(date: clock.now, steps: steps, workouts: [], sleep: nil)
    }

    func load(day: Date) async throws -> DailyHealthSnapshot {
        loadedDays.append(day)
        return DailyHealthSnapshot(date: day, steps: 4_000, workouts: [], sleep: nil)
    }

    func releaseToday() {
        holdToday = false
        held?.resume()
        held = nil
    }
}

/// HealthKit 없이 관찰 콜백·권한 확인을 흉내 낸다. 권한 확인은 `answers` 순서대로 답하고,
/// `holdAuthorization`이면 `releaseAuthorization()` 전까지 답하지 않는다.
@MainActor
final class FakeHealthUpdateObserver: HealthUpdateObserving {
    enum Answer {
        case requested
        case notRequested
        case unknown
    }

    var answers: [Answer] = [.requested]
    var holdAuthorization = false
    private(set) var startCount = 0
    private(set) var enableCount = 0
    private(set) var authorizationChecks = 0
    private var held: [CheckedContinuation<Void, Never>] = []
    private var handler: (@Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void)?

    nonisolated func startObserving(
        _ handler: @escaping @Sendable (HealthObservedType, Error?, @escaping @Sendable () -> Void) -> Void
    ) {
        MainActor.assumeIsolated {
            startCount += 1
            self.handler = handler
        }
    }

    nonisolated func isAuthorizationRequested() async throws -> Bool {
        try await answer()
    }

    nonisolated func enableBackgroundDelivery() {
        MainActor.assumeIsolated { enableCount += 1 }
    }

    private func answer() async throws -> Bool {
        authorizationChecks += 1
        if holdAuthorization {
            await withCheckedContinuation { held.append($0) }
        }
        let next = answers.count > 1 ? answers.removeFirst() : (answers.first ?? .notRequested)
        switch next {
        case .requested: return true
        case .notRequested: return false
        case .unknown: throw HealthDataError.unavailable
        }
    }

    func releaseAuthorization() {
        holdAuthorization = false
        let pending = held
        held = []
        pending.forEach { $0.resume() }
    }

    func fire(_ type: HealthObservedType, error: Error? = nil, completion: @escaping @Sendable () -> Void) {
        handler?(type, error, completion)
    }
}

/// 시작·종료를 기록하는 백그라운드 작업.
@MainActor
final class FakeBackgroundTasks: BackgroundTaskProviding {
    private(set) var begun = 0
    private(set) var ended: [Int] = []

    nonisolated init() {}

    func begin(name: String, expiration: @escaping @MainActor () -> Void) -> Int {
        begun += 1
        return begun
    }

    func end(_ identifier: Int) {
        ended.append(identifier)
    }
}

// MARK: - Watch

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

    /// 마지막으로 보낸 값. 로그아웃(빈 값)을 보냈거나 아직 보낸 적이 없으면 nil.
    var lastContext: WatchAuthContext? {
        published.last ?? nil
    }

    func publish(_ context: WatchAuthContext?) {
        published.append(context)
    }
}

/// 테스트가 실제 WCSession(`WatchAuthRelay.shared`)을 건드리지 않도록 `UserSession`에 주입한다.
final class NoopWatchPublisher: WatchAuthPublishing {
    func publish(_ context: WatchAuthContext?) {}
}
