import Foundation
import WidgetKit

/// Watch 화면 상태. 로그인 정보는 iPhone에서 받고, 캐릭터·오늘 활동은 서버에서 직접 조회한다.
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
    private let client: WatchAPIClient
    private let cache: SnapshotStore
    private let receiver: WatchAuthReceiver
    private let reloadWidgets: () -> Void
    private let now: () -> Date
    private var started = false

    init(
        store: WatchCredentialStore = KeychainWatchCredentialStore(),
        client: WatchAPIClient = WatchAPIClient(),
        cache: SnapshotStore = SnapshotStore(),
        receiver: WatchAuthReceiver = WatchAuthReceiver(),
        reloadWidgets: @escaping () -> Void = { WidgetCenter.shared.reloadAllTimelines() },
        now: @escaping () -> Date = Date.init
    ) {
        self.store = store
        self.client = client
        self.cache = cache
        self.receiver = receiver
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

    /// iPhone 로그인 정보 수신을 시작한다. 여러 번 불려도 한 번만 시작한다.
    func start() {
        guard !started else { return }
        started = true
        receiver.start { [weak self] context in
            Task { @MainActor in
                self?.receive(context)
            }
        }
    }

    func receive(_ applicationContext: [String: Any]) {
        let previous = credentials
        guard let updated = store.apply(applicationContext: applicationContext, now: now()) else {
            signOutLocally()
            return
        }
        credentials = updated
        if previous?.userId != updated.userId {
            // 다른 계정으로 바뀌었으면 이전 계정의 스냅샷을 보이지 않는다.
            snapshot = nil
            cache.clear()
        }
        if previous != updated {
            Task { await refresh() }
        }
    }

    func refresh() async {
        guard let current = store.currentCredentials(now: now()) else {
            signOutLocally()
            return
        }
        guard status != .loading else { return }
        credentials = current
        status = .loading

        do {
            let fetched = try await client.fetchSnapshot(credentials: current, now: now())
            // 조회 중에 로그아웃되거나 토큰이 바뀌었으면 결과를 버리고 새 정보로 다시 조회한다.
            guard store.load()?.accessToken == current.accessToken else {
                status = .idle
                await refresh()
                return
            }
            if let fetched {
                snapshot = fetched
                cache.save(fetched)
                status = .idle
            } else {
                status = .noCharacter
            }
            reloadWidgets()
        } catch WatchAPIError.unauthorized {
            store.delete()
            signOutLocally()
        } catch {
            status = .failed
        }
    }

    private func signOutLocally() {
        credentials = nil
        snapshot = nil
        status = .idle
        cache.clear()
        reloadWidgets()
    }
}
