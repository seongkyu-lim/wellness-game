import SwiftUI

@main
struct WellnessGameWatchApp: App {
    @StateObject private var model = WatchDashboardModel()

    var body: some Scene {
        WindowGroup {
            WatchContentView(model: model)
                .onAppear { model.start() }
        }
    }
}
