import SwiftUI

@main
struct WellnessGameApp: App {
    init() {
        SocialLoginService.initializeKakaoIfConfigured()
        SocialLoginService.initializeNaverIfConfigured()
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
