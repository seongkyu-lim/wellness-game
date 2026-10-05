import Foundation
import WatchConnectivity

/// iPhone이 `updateApplicationContext`로 보낸 로그인 정보를 받는다.
/// 앱이 꺼져 있는 동안 도착한 값은 다음 실행에서 세션이 활성화되면 시스템이 이어서 전달한다.
final class WatchAuthReceiver: NSObject, WCSessionDelegate {
    private var handler: (([String: Any]) -> Void)?

    /// 받은 컨텍스트를 넘길 곳을 정하고 세션을 활성화한다. 콜백은 백그라운드 큐에서 불린다.
    func start(handler: @escaping ([String: Any]) -> Void) {
        guard WCSession.isSupported() else { return }
        self.handler = handler
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        handler?(applicationContext)
    }
}
