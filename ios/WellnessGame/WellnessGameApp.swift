import SwiftUI

@main
struct WellnessGameApp: App {
    /// HealthKit 백그라운드 동기화를 실행 시점에 등록한다(백그라운드 재실행 포함).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        ServerEnvironment.validateAtLaunch()
        SocialLoginService.initializeKakaoIfConfigured()
        SocialLoginService.initializeNaverIfConfigured()
        // Watch로 로그인 정보를 보낼 WatchConnectivity를 켠다. 활성화 전에 발행된 값은 활성화가 끝나면 보낸다.
        WatchAuthRelay.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    _ = SocialLoginService.handleOpenURL(url)
                }
        }
    }
}
