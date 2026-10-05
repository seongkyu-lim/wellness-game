import SwiftUI

@main
struct WellnessGameApp: App {
    init() {
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
