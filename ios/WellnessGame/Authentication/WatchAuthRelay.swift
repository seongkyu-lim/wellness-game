import Foundation
import WatchConnectivity

/// `WCSession` 중 로그인 정보 전달에 필요한 부분. 테스트에서는 가짜 구현으로 대체한다.
protocol WatchContextSession: AnyObject {
    var activationState: WCSessionActivationState { get }
    var isPaired: Bool { get }
    var isWatchAppInstalled: Bool { get }
    func updateApplicationContext(_ applicationContext: [String: Any]) throws
}

extension WCSession: WatchContextSession {}

/// `UserSession`이 로그인 상태가 바뀔 때 Watch에 알리는 통로.
protocol WatchAuthPublishing: AnyObject {
    /// 로그인 정보를 보낸다. nil이면 로그아웃(빈 값)을 보낸다.
    func publish(_ context: WatchAuthContext?)
}

/// iPhone 로그인 정보를 WatchConnectivity application context로 Watch에 넘긴다.
///
/// - 세션 미지원(iPad 등)이면 아무것도 하지 않는다.
/// - 활성화 전이거나 페어링된 Watch·Watch 앱이 없으면 보내지 않고, 마지막 값을 들고 있다가
///   활성화·Watch 상태 변경(앱 설치, Watch 교체) 시 다시 보낸다. 전송 실패는 조용히 무시한다.
/// application context는 최신 값 하나만 유지되므로 마지막 값 하나만 보관한다.
final class WatchAuthRelay: NSObject, WatchAuthPublishing, @unchecked Sendable {
    static let shared = WatchAuthRelay(session: WCSession.isSupported() ? WCSession.default : nil)

    private let session: WatchContextSession?
    private let language: () -> String
    private let lock = NSLock()
    private var latest: [String: Any]?

    init(
        session: WatchContextSession?,
        language: @escaping () -> String = { AppLanguage.acceptLanguage() }
    ) {
        self.session = session
        self.language = language
    }

    /// 앱 시작 시 한 번 호출한다. 실제 `WCSession`일 때만 delegate를 연결하고 활성화한다.
    func activate() {
        guard let wcSession = session as? WCSession else { return }
        wcSession.delegate = self
        wcSession.activate()
    }

    func publish(_ context: WatchAuthContext?) {
        let payload = context?.applicationContext ?? WatchAuthContext.signedOutContext(language: language())
        lock.withLock { latest = payload }
        flush()
    }

    /// 보낼 수 있는 상태면 마지막 값을 보낸다.
    func flush() {
        guard let session,
              session.activationState == .activated,
              session.isPaired,
              session.isWatchAppInstalled else {
            return
        }
        guard let payload = lock.withLock({ latest }) else { return }
        try? session.updateApplicationContext(payload)
    }
}

extension WatchAuthRelay: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        flush()
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Watch를 바꿔 끼운 경우 새 Watch와 다시 연결한다.
        session.activate()
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        flush()
    }
}
