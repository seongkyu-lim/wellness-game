package com.example.wellnessgame.sync

import com.example.wellnessgame.auth.SessionManager
import com.example.wellnessgame.auth.StoredSession
import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.health.HealthAvailability
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.ApiJson
import com.example.wellnessgame.testing.CHARACTER_JSON
import com.example.wellnessgame.testing.FakeHealthProvider
import com.example.wellnessgame.testing.FakeSettings
import com.example.wellnessgame.testing.FakeTokenStore
import com.example.wellnessgame.testing.apiClient
import com.example.wellnessgame.testing.jsonResponse
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DefaultDashboardRepositoryTest {
    private lateinit var server: MockWebServer
    private val now = Instant.parse("2026-10-11T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val deviceSnapshot = DailyHealthSnapshot(LocalDate.of(2026, 10, 11), 1_234, emptyList(), null)
    private val mockSnapshot = DailyHealthSnapshot(LocalDate.of(2026, 10, 11), 8_500, emptyList(), null)
    private val healthConnect = FakeHealthProvider(deviceSnapshot)
    private val mock = FakeHealthProvider(mockSnapshot)
    private val settings = FakeSettings()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun TestScope.repository(signedIn: Boolean = true): DefaultDashboardRepository {
        val store = FakeTokenStore(if (signedIn) StoredSession("u-1", null, "google", "jwt") else null)
        val session = SessionManager(store, clock).also { it.restore() }
        return DefaultDashboardRepository(server.apiClient(session), session, healthConnect, mock, settings, clock)
    }

    private val syncBody =
        """{"userId":"u-1","date":"2026-10-11","gainedXp":5,"levelUp":false,"character":$CHARACTER_JSON,"activityResults":[]}"""

    @Test
    fun healthAccess_reflectsAvailabilityAndPermissions() = runTest {
        val repo = repository()
        assertEquals(HealthAccess.GRANTED, repo.healthAccess())
        healthConnect.granted = false
        assertEquals(HealthAccess.PERMISSION_REQUIRED, repo.healthAccess())
        healthConnect.availability = HealthAvailability.PROVIDER_UPDATE_REQUIRED
        assertEquals(HealthAccess.PROVIDER_UPDATE_REQUIRED, repo.healthAccess())
        healthConnect.availability = HealthAvailability.UNSUPPORTED
        assertEquals(HealthAccess.UNSUPPORTED, repo.healthAccess())
        settings.mockFlow.value = true
        assertEquals("mock data never needs Health Connect", HealthAccess.GRANTED, repo.healthAccess())
    }

    @Test
    fun syncToday_sendsSessionUserAndSnapshotDate_andRecordsSyncTime() = runTest {
        server.enqueue(jsonResponse(syncBody))
        val result = repository().syncToday()

        assertEquals(deviceSnapshot, result.snapshot)
        val body = ApiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("u-1", body["userId"]!!.jsonPrimitive.content)
        assertEquals("2026-10-11", body["date"]!!.jsonPrimitive.content)
        assertEquals(now, settings.lastFlow.value)
        assertEquals(1, healthConnect.loadCount)
        assertEquals(0, mock.loadCount)
    }

    @Test
    fun mockToggle_switchesTheDataSource() = runTest {
        settings.mockFlow.value = true
        server.enqueue(jsonResponse(syncBody))
        val repo = repository()
        assertTrue(repo.isUsingMockData())
        assertEquals(mockSnapshot, repo.syncToday().snapshot)
        assertEquals(0, healthConnect.loadCount)
    }

    @Test
    fun syncToday_withoutSession_failsBeforeReadingHealthData() = runTest {
        val repo = repository(signedIn = false)
        try {
            repo.syncToday()
            fail("expected Unauthorized")
        } catch (_: ApiException.Unauthorized) {
        }
        assertEquals(0, healthConnect.loadCount)
        assertEquals(0, server.requestCount)
        assertFalse(settings.lastFlow.value != null)
    }

    @Test
    fun todayQuests_queriesTheGivenDate() = runTest {
        server.enqueue(jsonResponse("""{"userId":"u-1","date":"2026-10-10","activities":[{"type":"STEPS","steps":7000,"gainedXp":0}]}"""))
        val quests = repository().todayQuests(LocalDate.of(2026, 10, 10))
        assertEquals(7_000, quests.steps)
        assertEquals("/api/health-activities?date=2026-10-10", server.takeRequest().path)
    }
}
