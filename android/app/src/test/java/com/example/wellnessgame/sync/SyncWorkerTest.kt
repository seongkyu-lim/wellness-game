package com.example.wellnessgame.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.health.HealthDataException
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.CharacterState
import com.example.wellnessgame.data.network.CharacterStats
import com.example.wellnessgame.data.network.HealthActivitySyncResponse
import com.example.wellnessgame.testing.FakeDashboardRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/** 실제 WellnessApp(Keystore·WorkManager 초기화) 대신 가짜 동기화를 넘기는 Application. */
class TestSyncApp : Application(), BackgroundSyncProvider {
    var signedIn = true
    lateinit var repository: FakeDashboardRepository
    override fun backgroundSync() = BackgroundSync({}, { signedIn }, repository)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncApp::class)
class SyncWorkerTest {
    private val snapshot = DailyHealthSnapshot(LocalDate.of(2026, 10, 11), 100, emptyList(), null)
    private val ok = SyncResult(
        snapshot,
        HealthActivitySyncResponse("u-1", "2026-10-11", 0, false, CharacterState(1, 0, 0, 100, CharacterStats(0, 0, 0, 0, 0))),
    )
    private lateinit var app: TestSyncApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as TestSyncApp
        app.signedIn = true
        app.repository = FakeDashboardRepository(snapshot = snapshot, syncResult = { ok })
    }

    private fun runWorker(): Result = runBlocking {
        TestListenableWorkerBuilder<SyncWorker>(app).build().doWork()
    }

    @Test
    fun syncs_whenSignedInWithPermission() {
        assertEquals(Result.success(), runWorker())
        assertEquals(1, app.repository.syncCount)
    }

    @Test
    fun skips_whenSignedOut_orPermissionMissing() {
        app.signedIn = false
        assertEquals(Result.success(), runWorker())
        app.signedIn = true
        app.repository.access = HealthAccess.PERMISSION_REQUIRED
        assertEquals(Result.success(), runWorker())
        assertEquals(0, app.repository.syncCount)
    }

    @Test
    fun neverSendsMockDataInTheBackground() {
        app.repository.mock = true
        assertEquals(Result.success(), runWorker())
        assertEquals(0, app.repository.syncCount)
    }

    @Test
    fun retries_onlyTransientFailures() {
        val cases = listOf(
            ApiException.Network(IOException("offline")) to Result.retry(),
            ApiException.RateLimited(30, null) to Result.retry(),
            ApiException.Server(503, null) to Result.retry(),
            ApiException.Server(400, "bad") to Result.success(),
            ApiException.Unauthorized(null) to Result.success(),
            ApiException.InvalidResponse() to Result.success(),
            HealthDataException(HealthDataException.Reason.PERMISSION_DENIED) to Result.success(),
        )
        cases.forEach { (error, expected) ->
            app.repository.syncResult = { throw error }
            assertEquals(error.toString(), expected, runWorker())
        }
    }
}
