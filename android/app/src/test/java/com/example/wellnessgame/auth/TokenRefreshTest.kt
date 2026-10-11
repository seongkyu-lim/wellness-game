package com.example.wellnessgame.auth

import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.ApiJson
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.testing.CHARACTER_JSON
import com.example.wellnessgame.testing.FakeSocialClient
import com.example.wellnessgame.testing.FakeTokenStore
import com.example.wellnessgame.testing.apiClient
import com.example.wellnessgame.testing.jsonResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Auth v2: 401 → 리프레시 → 재시도, 단일 비행, 사전 갱신, 회전 저장, 로그아웃(MockWebServer + 실제 [ApiClient]·[SessionManager]). */
class TokenRefreshTest {
    private lateinit var server: MockWebServer
    private val start = Instant.parse("2026-10-11T03:00:00Z")
    private val clock = Clock.fixed(start, ZoneOffset.UTC)
    private val now get() = start.epochSecond

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun session(
        access: String = "old",
        expiresAt: Long? = now + 3600,
        refresh: String? = "r-1",
        refreshExpiresAt: Long? = now + 30 * 86_400,
    ) = StoredSession("u-1", "새싹", "kakao", access, expiresAt, refresh, refreshExpiresAt)

    private suspend fun signedIn(store: FakeTokenStore): Pair<SessionManager, ApiClient> {
        val manager = SessionManager(store, clock).also { it.restore() }
        return manager to server.apiClient(manager)
    }

    private fun refreshBody(access: String = "new", refresh: String = "r-2") =
        """{"userId":"u-1","accessToken":"$access","tokenType":"Bearer","expiresIn":3600,"refreshToken":"$refresh","refreshExpiresIn":2592000}"""

    private val characterBody = """{"userId":"u-1","character":$CHARACTER_JSON}"""
    private fun unauthorized() = jsonResponse("""{"message":"인증이 필요합니다."}""", 401)

    private fun RecordedRequest.bodyText() = body.readUtf8()

    @Test
    fun unauthorized_refreshesOnce_retriesWithNewToken_andSavesRotation() = runTest {
        val store = FakeTokenStore(session())
        val (_, api) = signedIn(store)
        server.enqueue(unauthorized())
        server.enqueue(jsonResponse(refreshBody()))
        server.enqueue(jsonResponse(characterBody))

        assertNotNull(api.myCharacter())

        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer old", first.getHeader("Authorization"))
        val refresh = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/refresh", refresh.path)
        assertEquals("POST", refresh.method)
        assertNull("refresh is a public endpoint", refresh.getHeader("Authorization"))
        assertEquals("""{"refreshToken":"r-1"}""", refresh.bodyText())
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/characters/me", retry.path)
        assertEquals("Bearer new", retry.getHeader("Authorization"))

        assertEquals(
            session(access = "new", refresh = "r-2", expiresAt = now + 3600, refreshExpiresAt = now + 2_592_000),
            store.stored,
        )
    }

    @Test
    fun refreshRejected_clearsSession_andAsksToLogInAgain() = runTest {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        server.enqueue(unauthorized())
        server.enqueue(unauthorized())

        try {
            api.myCharacter()
            fail("expected Unauthorized")
        } catch (_: ApiException.Unauthorized) {
        }
        assertEquals(2, server.requestCount)
        assertNull(store.stored)
        assertFalse(manager.state.value.isSignedIn)
        assertTrue(manager.state.value.reloginRequired)
    }

    @Test
    fun retryAlsoUnauthorized_endsSession_withoutSecondRefresh() = runTest {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        server.enqueue(unauthorized())
        server.enqueue(jsonResponse(refreshBody()))
        server.enqueue(unauthorized())

        runCatching { api.myCharacter() }.exceptionOrNull() as ApiException.Unauthorized
        assertEquals(3, server.requestCount)
        assertFalse(manager.state.value.isSignedIn)
        assertNull(store.stored)
    }

    @Test
    fun transientRefreshFailure_keepsSession() = runTest {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setResponseCode(503))

        val error = runCatching { api.myCharacter() }.exceptionOrNull()
        assertTrue(error is ApiException.Server)
        assertTrue(manager.state.value.isSignedIn)
        assertEquals(session(), store.stored)
    }

    @Test
    fun concurrentUnauthorized_sendsASingleRefresh() = runTest {
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    jsonResponse(refreshBody()).setBodyDelay(200, TimeUnit.MILLISECONDS)
                }
                request.getHeader("Authorization") == "Bearer new" -> jsonResponse(characterBody)
                else -> unauthorized()
            }
        }
        val store = FakeTokenStore(session())
        val (_, api) = signedIn(store)

        val results = List(3) { async { api.myCharacter() } }.awaitAll()

        assertTrue(results.all { it != null })
        assertEquals(1, refreshCount.get())
        assertEquals("r-2", store.stored?.refreshToken)
    }

    @Test
    fun unauthorizedForTokenAlreadyRenewedElsewhere_reusesNewToken_withoutRefresh() = runTest {
        // 다른 요청이 이미 "old"를 "new"로 갱신한 뒤에 "old"의 401이 늦게 도착한 상황.
        val manager = SessionManager(FakeTokenStore(session(access = "new", refresh = "r-2")), clock)
        manager.restore()
        val renewed = manager.renewAfterUnauthorized("old") { error("must not refresh again") }
        assertEquals("new", renewed)
        assertTrue(manager.state.value.isSignedIn)
    }

    @Test
    fun accessExpiringWithinLeeway_isRefreshedBeforeTheRequest() = runTest {
        val store = FakeTokenStore(session(expiresAt = now + 30))
        val (_, api) = signedIn(store)
        server.enqueue(jsonResponse(refreshBody()))
        server.enqueue(jsonResponse(characterBody))

        api.myCharacter()

        assertEquals("/api/auth/refresh", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals("Bearer new", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals("new", store.stored?.accessToken)
    }

    @Test
    fun expiredAccess_withValidRefresh_staysSignedInAfterRestore_andRefreshes() = runTest {
        val store = FakeTokenStore(session(expiresAt = now - 10))
        val (manager, api) = signedIn(store)
        assertTrue(manager.state.value.isSignedIn)
        server.enqueue(jsonResponse(refreshBody()))
        server.enqueue(jsonResponse(characterBody))

        api.myCharacter()
        assertEquals("/api/auth/refresh", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals("Bearer new", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun preRefreshNetworkFailure_fallsBackToStillValidToken() = runTest {
        val store = FakeTokenStore(session(expiresAt = now + 30))
        val (_, api) = signedIn(store)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(jsonResponse(characterBody))

        api.myCharacter()
        server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer old", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals(session(expiresAt = now + 30), store.stored)
    }

    @Test
    fun saveFailure_keepsPreviousRefreshTokenOnDisk_butUsesNewTokenInMemory() = runTest {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        store.saveFailure = IOException("keystore unavailable")
        server.enqueue(unauthorized())
        server.enqueue(jsonResponse(refreshBody()))
        server.enqueue(jsonResponse(characterBody))
        server.enqueue(jsonResponse(characterBody))

        api.myCharacter()
        api.myCharacter()

        assertEquals("previous refresh token stays for the server grace window", session(), store.stored)
        assertEquals(0, store.clearCount)
        assertTrue(manager.state.value.isSignedIn)
        repeat(3) { server.takeRequest(5, TimeUnit.SECONDS)!! }
        assertEquals("Bearer new", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun legacySessionWithoutRefresh_worksUntil401_thenLogsOutWithoutRefreshing() = runTest {
        val store = FakeTokenStore(session(refresh = null, refreshExpiresAt = null))
        val (manager, api) = signedIn(store)
        server.enqueue(jsonResponse(characterBody))
        server.enqueue(unauthorized())

        assertNotNull(api.myCharacter())
        assertEquals("Bearer old", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        runCatching { api.myCharacter() }.exceptionOrNull() as ApiException.Unauthorized

        assertEquals("no refresh request for legacy sessions", 2, server.requestCount)
        assertTrue(manager.state.value.reloginRequired)
        assertNull(store.stored)
    }

    @Test
    fun legacyStoredJson_decodesWithoutRefreshFields() {
        val legacy = """{"userId":"u-1","provider":"google","accessToken":"jwt","expiresAtEpochSeconds":1}"""
        val decoded = ApiJson.decodeFromString(StoredSession.serializer(), legacy)
        assertEquals(StoredSession("u-1", null, "google", "jwt", 1), decoded)
        assertNull(decoded.refreshToken)
    }

    @Test
    fun restore_clearsSession_whenBothAccessAndRefreshExpired() = runTest {
        val store = FakeTokenStore(session(expiresAt = now - 10, refreshExpiresAt = now - 1))
        val manager = SessionManager(store, clock).also { it.restore() }
        assertFalse(manager.state.value.isSignedIn)
        assertTrue(manager.state.value.reloginRequired)
        assertNull(store.stored)
    }

    @Test
    fun signIn_storesRefreshTokenAndExpiry() = runTest {
        val store = FakeTokenStore()
        val manager = SessionManager(store, clock).also { it.restore() }
        manager.signIn(AuthTokenResponse("u-1", "새싹", "a", "Bearer", 600, "r", 86_400), LoginProvider.KAKAO)
        assertEquals(StoredSession("u-1", "새싹", "kakao", "a", now + 600, "r", now + 86_400), store.stored)
    }

    // 로그아웃은 실제 시간 제한(withTimeoutOrNull)을 쓰므로 가상 시간(runTest) 대신 runBlocking으로 돌린다.
    @Test
    fun logout_sendsOnlyRefreshToken_withoutAuthorization_andSignsOutOfSdk() = runBlocking {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        val kakao = FakeSocialClient(LoginProvider.KAKAO)
        server.enqueue(MockResponse().setResponseCode(204))

        AuthRepository(api, manager, mapOf(LoginProvider.KAKAO to kakao)).signOut()

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/logout", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertEquals("""{"refreshToken":"r-1"}""", request.bodyText())
        assertNull(store.stored)
        assertFalse(manager.state.value.isSignedIn)
        assertFalse(manager.state.value.reloginRequired)
        assertEquals(1, kakao.signOutCount)
    }

    @Test
    fun logout_serverFailure_stillLogsOutLocally() = runBlocking {
        val store = FakeTokenStore(session())
        val (manager, api) = signedIn(store)
        val kakao = FakeSocialClient(LoginProvider.KAKAO)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        AuthRepository(api, manager, mapOf(LoginProvider.KAKAO to kakao)).signOut()

        assertNull(store.stored)
        assertFalse(manager.state.value.isSignedIn)
        assertEquals(1, kakao.signOutCount)
    }

    @Test
    fun logout_legacySession_sendsNothing() = runBlocking {
        val store = FakeTokenStore(session(refresh = null, refreshExpiresAt = null))
        val (manager, api) = signedIn(store)
        AuthRepository(api, manager, emptyMap()).signOut()
        assertEquals(0, server.requestCount)
        assertFalse(manager.state.value.isSignedIn)
    }
}
