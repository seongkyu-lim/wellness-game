package io.github.seongkyulim.wellnessgame

import android.app.Application
import io.github.seongkyulim.wellnessgame.sync.BackgroundSync
import io.github.seongkyulim.wellnessgame.sync.BackgroundSyncProvider
import io.github.seongkyulim.wellnessgame.sync.SyncWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class WellnessApp : Application(), BackgroundSyncProvider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 로그인하면 1시간 주기 동기화를 걸고, 로그아웃·401이면 취소한다.
        container.appScope.launch {
            container.session.state
                .filter { it.restored }
                .map { it.isSignedIn }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (signedIn) SyncWorker.schedule(this@WellnessApp) else SyncWorker.cancel(this@WellnessApp)
                }
        }
    }

    override fun backgroundSync() = BackgroundSync(
        awaitSessionRestored = container::awaitSessionRestored,
        isSignedIn = { container.session.state.value.isSignedIn },
        repository = container.dashboard,
    )
}
