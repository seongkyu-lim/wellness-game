import Foundation
import WatchConnectivity
import WatchKit

/// WCSession을 켜고 iPhone이 보낸 로그인 정보를 모델에 반영한다.
///
/// - 활성화가 끝나면, 그리고 이미 활성화된 상태에서 `start()`가 불리면 `receivedApplicationContext`를 즉시 적용한다.
/// - 앱이 백그라운드일 때 받은 값은 `WKWatchConnectivityRefreshBackgroundTask`로 깨어나 처리하고,
///   받을 내용이 더 없을 때 작업을 끝낸다. 토큰 저장·삭제와 컴플리케이션 갱신은 모델이 맡는다.
@MainActor
final class WatchAuthReceiver: NSObject {
    private let model: WatchDashboardModel
    private var pendingTasks: [WKWatchConnectivityRefreshBackgroundTask] = []
    private var applying = 0

    init(model: WatchDashboardModel) {
        self.model = model
    }

    func start() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        if session.delegate !== self {
            session.delegate = self
        }
        if session.activationState == .activated {
            apply(session.receivedApplicationContext)
        } else {
            session.activate()
        }
    }

    func handle(_ task: WKWatchConnectivityRefreshBackgroundTask) {
        pendingTasks.append(task)
        start()
        completeTasksIfIdle()
    }

    private func apply(_ applicationContext: [String: Any]) {
        guard !applicationContext.isEmpty else {
            completeTasksIfIdle()
            return
        }
        applying += 1
        Task {
            if model.receive(applicationContext) {
                await model.refresh()
            }
            applying -= 1
            completeTasksIfIdle()
        }
    }

    private func completeTasksIfIdle() {
        let session = WCSession.default
        guard !pendingTasks.isEmpty, applying == 0,
              session.activationState == .activated, !session.hasContentPending else {
            return
        }
        pendingTasks.forEach { $0.setTaskCompletedWithSnapshot(false) }
        pendingTasks.removeAll()
    }
}

extension WatchAuthReceiver: WCSessionDelegate {
    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        let context = activationState == .activated ? session.receivedApplicationContext : [:]
        Task { @MainActor in
            self.apply(context)
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        Task { @MainActor in
            self.apply(applicationContext)
        }
    }
}
