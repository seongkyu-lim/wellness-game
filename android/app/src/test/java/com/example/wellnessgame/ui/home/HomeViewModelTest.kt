package com.example.wellnessgame.ui.home

import com.example.wellnessgame.auth.SessionManager
import com.example.wellnessgame.auth.StoredSession
import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.health.HealthActivityMapper
import com.example.wellnessgame.data.network.ActivityXpResult
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.CharacterState
import com.example.wellnessgame.data.network.CharacterStats
import com.example.wellnessgame.data.network.HealthActivitySyncResponse
import com.example.wellnessgame.domain.DailyQuestProgress
import com.example.wellnessgame.sync.DashboardRepository
import com.example.wellnessgame.sync.HealthAccess
import com.example.wellnessgame.sync.SyncResult
import com.example.wellnessgame.testing.FakeSettings
import com.example.wellnessgame.testing.FakeTokenStore
import com.example.wellnessgame.testing.MainDispatcherRule
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HomeViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val now = Instant.parse("2026-10-11T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val character = CharacterState(3, 40, 240, 150, CharacterStats(1, 2, 3, 4, 5))
    private val snapshot = DailyHealthSnapshot(
        date = LocalDate.of(2026, 10, 11),
        steps = 8_500,
        workouts = emptyList(),
        sleep = HealthActivityMapper.sleepActivity(
            listOf(com.example.wellnessgame.data.health.TimeInterval(now.minusSeconds(8 * 3600), now)),
        ),
    )

    private class FakeRepository(
        var access: HealthAccess = HealthAccess.GRANTED,
        var syncResult: () -> SyncResult,
        var character: CharacterState? = null,
        var quests: () -> DailyQuestProgress = { DailyQuestProgress(9_000, 10, 400) },
        val snapshot: DailyHealthSnapshot,
    ) : DashboardRepository {
        var syncCount = 0
        var characterCount = 0
        override suspend fun healthAccess() = access
        override suspend fun permissionsToRequest() = setOf("perm")
        override suspend fun loadSnapshot() = snapshot
        override suspend fun syncToday(): SyncResult {
            syncCount++
            return syncResult()
        }
        override suspend fun character(): CharacterState? {
            characterCount++
            return character
        }
        override suspend fun todayQuests() = quests()
    }

    private fun syncResult(levelUp: Boolean = false) = SyncResult(
        snapshot,
        HealthActivitySyncResponse(
            userId = "u-1",
            date = "2026-10-11",
            gainedXp = 120,
            levelUp = levelUp,
            character = character,
            activityResults = listOf(ActivityXpResult("STEPS", "s", 100, "걸음", false)),
        ),
    )

    private fun repository() = FakeRepository(syncResult = { syncResult() }, snapshot = snapshot)

    private suspend fun TestScope.session(signedIn: Boolean): SessionManager {
        val store = FakeTokenStore(if (signedIn) StoredSession("u-1", null, "google", "jwt") else null)
        return SessionManager(store, backgroundScope, clock).also { it.restore() }
    }

    @Test
    fun signedOut_doesNotSync_andAsksToLogIn() = runTest {
        val repo = repository()
        val vm = HomeViewModel(repo, session(signedIn = false), FakeSettings(), clock)
        advanceUntilIdle()
        assertEquals(HomeStatus.SignInRequired, vm.state.value.status)
        assertEquals(0, repo.syncCount)
        assertNull(vm.state.value.quests)
    }

    @Test
    fun signedOut_withMock_showsLocalPreviewOnly() = runTest {
        val repo = repository()
        val vm = HomeViewModel(repo, session(signedIn = false), FakeSettings(mock = true), clock)
        advanceUntilIdle()
        assertEquals(HomeStatus.MockPreview, vm.state.value.status)
        assertEquals(8_500, vm.state.value.quests?.steps)
        assertEquals(0, repo.syncCount)
    }

    @Test
    fun signedIn_withPermission_syncsAndPrefersServerQuests() = runTest {
        val repo = repository()
        val vm = HomeViewModel(repo, session(signedIn = true), FakeSettings(), clock)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(1, repo.syncCount)
        assertEquals(HomeStatus.Synced(levelUp = false), state.status)
        assertEquals(character, state.character)
        assertEquals(120, state.gainedXp)
        assertEquals(1, state.results.size)
        assertEquals(DailyQuestProgress(9_000, 10, 400), state.quests)
        assertFalse(state.isLoading)
    }

    @Test
    fun serverQuestFailure_fallsBackToDeviceSnapshot() = runTest {
        val repo = repository().apply { quests = { throw ApiException.Server(500, null) } }
        val vm = HomeViewModel(repo, session(signedIn = true), FakeSettings(), clock)
        advanceUntilIdle()
        assertEquals(DailyQuestProgress(8_500, 0, 480), vm.state.value.quests)
        assertEquals(HomeStatus.Synced(false), vm.state.value.status)
    }

    @Test
    fun missingPermission_skipsSync_butShowsServerCharacter() = runTest {
        val repo = repository().apply {
            access = HealthAccess.PERMISSION_REQUIRED
            character = this@HomeViewModelTest.character
        }
        val vm = HomeViewModel(repo, session(signedIn = true), FakeSettings(), clock)
        advanceUntilIdle()
        assertEquals(0, repo.syncCount)
        assertEquals(HomeStatus.HealthPermissionRequired, vm.state.value.status)
        assertEquals(HealthAccess.PERMISSION_REQUIRED, vm.state.value.healthAccess)
        assertEquals(character, vm.state.value.character)
    }

    @Test
    fun rateLimited_showsRetryAfter() = runTest {
        val repo = repository().apply { syncResult = { throw ApiException.RateLimited(30, "slow down") } }
        val vm = HomeViewModel(repo, session(signedIn = true), FakeSettings(), clock)
        advanceUntilIdle()
        assertEquals(HomeStatus.RateLimited(30), vm.state.value.status)
    }

    @Test
    fun unauthorized_endsSession_andShowsExpiredNotice() = runTest {
        val session = session(signedIn = true)
        val repo = repository().apply {
            syncResult = {
                session.onUnauthorized("jwt") // ApiClient가 하는 일
                throw ApiException.Unauthorized(null)
            }
        }
        val vm = HomeViewModel(repo, session, FakeSettings(), clock)
        advanceUntilIdle()
        assertFalse(vm.state.value.session.isSignedIn)
        assertEquals(HomeStatus.SessionExpired, vm.state.value.status)
        assertNull(vm.state.value.character)
    }

    @Test
    fun foreground_withinMinimumInterval_doesNotResync() = runTest {
        val repo = repository()
        val settings = FakeSettings()
        val vm = HomeViewModel(repo, session(signedIn = true), settings, clock)
        advanceUntilIdle()
        settings.lastFlow.value = now // 방금 동기화함
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, repo.syncCount)
        vm.refresh(force = true)
        advanceUntilIdle()
        assertEquals(2, repo.syncCount)
    }

    @Test
    fun togglingMock_persistsAndResyncs() = runTest {
        val repo = repository()
        val settings = FakeSettings()
        val vm = HomeViewModel(repo, session(signedIn = true), settings, clock)
        advanceUntilIdle()
        vm.setUseMockData(true)
        advanceUntilIdle()
        assertEquals(true, settings.mockFlow.value)
        assertEquals(true, vm.state.value.useMockData)
        assertEquals(2, repo.syncCount)
    }
}
