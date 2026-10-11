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

    /// Debug `-WorkoutDemo`면 가짜 세션, 아니면 HealthKit. 복구도 같은 컨트롤러로 해서 모드가 어긋나지 않게 한다.
    private lazy var workoutController: WorkoutSessionControlling = {
        #if DEBUG
        if DemoWorkoutController.isEnabled {
            return DemoWorkoutController()
        }
        #endif
        return makeHealthKitWorkoutController()
    }()
    lazy var workout = WorkoutViewModel(controller: workoutController)

    func applicationDidFinishLaunching() {
        ServerEnvironment.validateAtLaunch()
        receiver.start()
        scheduleBackgroundRefresh()
    }

    /// 운동 중 앱이 종료됐다가 시스템이 다시 실행하면 진행 중인 세션을 이어받는다.
    func handleActiveWorkoutRecovery() {
        _ = workout // 뷰모델이 먼저 컨트롤러 콜백을 연결해야 복구 이벤트를 받는다.
        Task { await workoutController.recoverActiveSession() }
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
