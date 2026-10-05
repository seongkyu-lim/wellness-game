import Combine
import Foundation

/// Watch 화면 상태. 로그인 정보는 iPhone에서 받고, 캐릭터·오늘 활동은 서버에서 직접 조회한다.
///
/// WatchConnectivity·WidgetKit에 의존하지 않도록 두어 iOS 테스트 타깃에서도 검증한다.
/// - 받은 로그인 정보는 메모리(`credentials`)에 보관하고 그 값으로 조회한다. Keychain 저장이 실패해도
///   이번 실행 동안은 계속 쓸 수 있다.
/// - 같은 컨텍스트가 다시 와도 Keychain에 없으면(401로 지운 뒤 등) 다시 저장하고 조회한다.
@MainActor
final class WatchDashboardModel: ObservableObject {
    enum Status: Equatable {
        case idle
        case loading
        /// 서버에 아직 캐릭터가 없다(iPhone에서 첫 동기화 전).
        case noCharacter
        case failed
    }

    @Published private(set) var credentials: WatchAuthContext?
    @Published private(set) var snapshot: CharacterSnapshot?
    @Published private(set) var status: Status = .idle

    private let store: WatchCredentialStore
    private let client: WatchSnapshotFetching
    private let cache: SnapshotStore
    private let reloadWidgets: () -> Void
    private let now: () -> Date

    init(
        store: WatchCredentialStore,
        client: WatchSnapshotFetching,
        cache: SnapshotStore,
        reloadWidgets: @escaping () -> Void,
        now: @escaping () -> Date = Date.init
    ) {
        self.store = store
        self.client = client
        self.cache = cache
        self.reloadWidgets = reloadWidgets
        self.now = now
        credentials = store.currentCredentials(now: now())
        snapshot = credentials == nil ? nil : cache.load()
    }

    var isSignedIn: Bool {
        credentials != nil
    }

    var isLoading: Bool {
        status == .loading
    }

    func isSnapshotStale() -> Bool {
        snapshot?.isStale(at: now()) ?? false
    }

    /// iPhone이 보낸 application context를 반영한다.
    /// - Returns: 새 로그인 정보를 받아 서버를 다시 조회해야 하면 true.
    @discardableResult
    func receive(_ applicationContext: [String: Any]) -> Bool {
        guard let incoming = WatchAuthContext(applicationContext: applicationContext),
              !incoming.isExpired(at: now()) else {
            // 로그아웃(빈 값)·만료: 토큰과 캐시를 지우고 컴플리케이션도 바로 갱신한다.
            store.delete()
            signOutLocally()
            return false
        }

        // 중복 판단은 현재 Keychain 상태 기준이다. 메모리와 Keychain 모두 같을 때만 무시한다.
        if store.load() == incoming, credentials == incoming {
            return false
        }
        if let previousUserId = credentials?.userId, previousUserId != incoming.userId {
            // 다른 계정으로 바뀌었으면 이전 계정의 스냅샷을 보이지 않는다.
            snapshot = nil
            cache.clear()
            reloadWidgets()
        }
        try? store.save(incoming)
        credentials = incoming
        return true
    }

    /// 메모리의 로그인 정보로 서버를 조회해 화면·캐시·컴플리케이션을 갱신한다.
    func refresh() async {
        guard let current = credentials else { return }
        guard !current.isExpired(at: now()) else {
            store.delete()
            signOutLocally()
            return
        }
        guard status != .loading else { return }
        status = .loading

        do {
            let fetched = try await client.fetchSnapshot(credentials: current, now: now())
            guard await isStillCurrent(current) else { return }
            if let fetched {
                snapshot = fetched
                cache.save(fetched)
                status = .idle
            } else {
                status = .noCharacter
            }
            reloadWidgets()
        } catch WatchAPIError.unauthorized {
            guard await isStillCurrent(current) else { return }
            store.delete()
            signOutLocally()
        } catch {
            guard await isStillCurrent(current) else { return }
            status = .failed
        }
    }

    /// 조회 중에 로그아웃되거나 토큰이 바뀌었으면 결과를 버리고, 새 정보가 있으면 다시 조회한다.
    private func isStillCurrent(_ requested: WatchAuthContext) async -> Bool {
        guard credentials?.accessToken != requested.accessToken else { return true }
        status = .idle
        await refresh()
        return false
    }

    private func signOutLocally() {
        credentials = nil
        snapshot = nil
        status = .idle
        cache.clear()
        reloadWidgets()
    }
}
