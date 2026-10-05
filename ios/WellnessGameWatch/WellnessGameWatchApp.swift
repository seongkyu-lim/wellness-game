import SwiftUI

@main
struct WellnessGameWatchApp: App {
    @WKApplicationDelegateAdaptor private var delegate: WatchAppDelegate

    var body: some Scene {
        WindowGroup {
            WatchContentView(model: delegate.model)
        }
    }
}
