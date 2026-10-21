import SwiftUI
import UIKit

/// 앱 전체가 함께 쓰는 세션·네트워크·동기화 객체.
///
/// 화면(`ContentView`)과 백그라운드 동기화가 같은 인스턴스를 써야 리프레시 토큰 회전이 한 번에 하나만 나가고,
/// 백그라운드에서 세션이 끝나면(401) 화면에도 바로 반영된다.
/// 세션은 처음 쓸 때 만든다. 첫 잠금 해제 전 백그라운드 실행에서 미리 만들면 Keychain·UserDefaults를 읽지 못해
/// 로그아웃 상태로 복원되기 때문이다.
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    // 세션과 네트워크가 같은 자격 증명 저장소를 공유해야 로그인·리프레시 직후 토큰이 헤더에 실리고,
    // 리프레시가 한 번에 하나만 나간다.
    let credentials = SessionCredentialStore()
    private let healthKitManager = HealthKitManager()

    lazy var networkClient = NetworkClient(credentials: credentials)
    lazy var userSession = UserSession(credentials: credentials)
    lazy var syncService = HealthSyncService(networkClient: networkClient, userSession: userSession)

    lazy var backgroundSync: HealthBackgroundSync = {
        let runner = BackgroundHealthSyncRunner(
            isProtectedDataAvailable: { UIApplication.shared.isProtectedDataAvailable },
            checkCredentialsReadable: { [credentials] in try credentials.checkReadable() },
            syncService: { [unowned self] in self.syncService },
            // 백그라운드는 항상 실제 HealthKit 데이터를 보낸다(Mock 토글은 개발 화면 전용).
            provider: AppleHealthKitProvider(manager: healthKitManager)
        )
        let coordinator = BackgroundSyncCoordinator {
            _ = await runner.run()
        }
        return HealthBackgroundSync(
            observer: HealthKitUpdateObserver(manager: healthKitManager),
            coordinator: coordinator
        )
    }()

    private init() {}
}

/// iOS가 HealthKit 백그라운드 전달로 앱을 다시 실행할 때도 관찰을 등록하도록 실행 시점에 시작한다.
/// Apple은 관찰 쿼리를 `application(_:didFinishLaunchingWithOptions:)`에서 등록하라고 안내한다.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let backgroundSync = AppEnvironment.shared.backgroundSync
        Task { await backgroundSync.startIfAuthorized() }
        return true
    }
}
