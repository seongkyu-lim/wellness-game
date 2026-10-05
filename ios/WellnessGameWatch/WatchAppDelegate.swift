import WatchKit
import WidgetKit

/// 앱 수명 주기와 백그라운드 작업을 처리한다.
/// WCSession은 화면이 아니라 여기(실행 직후)에서 켜서 백그라운드 실행 때도 로그인 정보를 받는다.
@MainActor
final class WatchAppDelegate: NSObject, WKApplicationDelegate {
    /// 30분마다 서버를 다시 조회해 캐시와 컴플리케이션을 갱신한다.
    static let backgroundRefreshInterval: TimeInterval = 30 * 60

    lazy var model = WatchDashboardModel(
        store: KeychainWatchCredentialStore(),
        client: WatchAPIClient(),
        cache: SnapshotStore(),
        reloadWidgets: { WidgetCenter.shared.reloadAllTimelines() }
    )
    private lazy var receiver = WatchAuthReceiver(model: model)

    func applicationDidFinishLaunching() {
        ServerEnvironment.validateAtLaunch()
        receiver.start()
        scheduleBackgroundRefresh()
    }

    func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
        for task in backgroundTasks {
            switch task {
            case let connectivityTask as WKWatchConnectivityRefreshBackgroundTask:
                receiver.handle(connectivityTask)
            case let refreshTask as WKApplicationRefreshBackgroundTask:
                Task {
                    await model.refresh()
                    scheduleBackgroundRefresh()
                    refreshTask.setTaskCompletedWithSnapshot(false)
                }
            case let snapshotTask as WKSnapshotRefreshBackgroundTask:
                snapshotTask.setTaskCompleted(restoredDefaultState: true, estimatedSnapshotExpiration: .distantFuture, userInfo: nil)
            default:
                task.setTaskCompletedWithSnapshot(false)
            }
        }
    }

    private func scheduleBackgroundRefresh() {
        WKApplication.shared().scheduleBackgroundRefresh(
            withPreferredDate: Date().addingTimeInterval(Self.backgroundRefreshInterval),
            userInfo: nil
        ) { _ in }
    }
}
