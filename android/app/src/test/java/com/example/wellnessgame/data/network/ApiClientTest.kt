package com.example.wellnessgame.data.network

import com.example.wellnessgame.testing.FakeTokenSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenSource
    private lateinit var client: ApiClient
    private var language = "ko"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = FakeTokenSource("token-1")
        val http = NetworkModule.okHttpClient(language = { language })
        client = ApiClient(NetworkModule.api(server.url("/").toString(), http), tokens)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val authBody = """{"userId":"u-1","displayName":"새싹","accessToken":"jwt","tokenType":"Bearer","expiresIn":3600,"userIdentifier":"legacy"}"""

    private val characterJson = """{"level":3,"currentXp":40,"totalXp":240,"nextLevelXp":150,"stats":{"str":1,"vit":2,"intStat":3,"discipline":4,"recovery":5}}"""

    @Test
    fun login_postsCredentials_withAcceptLanguage_andWithoutAuthorization() = runTest {
        server.enqueue(json(authBody))
        val response = client.logIn("  sprout  ".trim(), "password123")

        assertEquals(AuthTokenResponse("u-1", "새싹", "jwt", "Bearer", 3600), response)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/login", request.path)
        assertEquals("ko", request.getHeader("Accept-Language"))
        assertNull(request.getHeader("Authorization"))
        val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("sprout", body["username"]!!.jsonPrimitive.content)
        assertEquals("password123", body["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun socialExchanges_hitNativeEndpoints_withSdkTokens() = runTest {
        repeat(3) { server.enqueue(json(authBody)) }
        client.signInWithGoogle("google-id-token")
        client.signInWithKakao("kakao-access")
        client.signInWithNaver("naver-access", "naver-refresh")

        val google = server.takeRequest()
        assertEquals("/api/auth/google/native", google.path)
        assertEquals("""{"idToken":"google-id-token"}""", google.body.readUtf8())
        val kakao = server.takeRequest()
        assertEquals("/api/auth/kakao/native", kakao.path)
        assertEquals("""{"accessToken":"kakao-access"}""", kakao.body.readUtf8())
        val naver = server.takeRequest()
        assertEquals("/api/auth/naver/native", naver.path)
        assertEquals("""{"accessToken":"naver-access","refreshToken":"naver-refresh"}""", naver.body.readUtf8())
    }

    @Test
    fun acceptLanguage_followsCurrentAppLanguage() = runTest {
        language = "en"
        server.enqueue(json(authBody))
        client.signUp("sprout", "password123")
        val request = server.takeRequest()
        assertEquals("/api/auth/signup", request.path)
        assertEquals("en", request.getHeader("Accept-Language"))
    }

    @Test
    fun sync_sendsBearerToken_andDecodesResponse() = runTest {
        server.enqueue(
            json(
                """{"userId":"u-1","date":"2026-10-11","gainedXp":120,"levelUp":true,"character":$characterJson,
                   "activityResults":[{"type":"STEPS","source":"STEPS 2026-10-11","gainedXp":100,"message":"걸음 8,500보","duplicate":false}],
                   "goals":[{"type":"STEPS","target":8000,"current":8500,"unit":"steps","achieved":true}]}""",
            ),
        )
        val response = client.sync(
            HealthActivitySyncRequest("u-1", "2026-10-11", listOf(HealthActivityPayload(type = "STEPS", steps = 8_500))),
        )

        assertEquals(120, response.gainedXp)
        assertTrue(response.levelUp)
        assertEquals(CharacterStats(1, 2, 3, 4, 5), response.character.stats)
        assertEquals("걸음 8,500보", response.activityResults.single().message)

        val request = server.takeRequest()
        assertEquals("/api/health-activities/sync", request.path)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("ko", request.getHeader("Accept-Language"))
        val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("2026-10-11", body["date"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "steps"), body["activities"]!!.jsonArray.single().jsonObject.keys)
    }

    @Test
    fun readEndpoints_useMeAndDateQuery() = runTest {
        server.enqueue(json("""{"userId":"u-1","character":$characterJson}"""))
        server.enqueue(json("""{"userId":"u-1","date":"2026-10-11","activities":[{"type":"STEPS","source":"s","steps":10,"gainedXp":1,"calories":null}]}"""))

        assertEquals(3, client.myCharacter()!!.character.level)
        val daily = client.dailyActivities("2026-10-11")
        assertEquals(10, daily.activities.single().steps)

        assertEquals("/api/characters/me", server.takeRequest().path)
        val dailyRequest = server.takeRequest()
        assertEquals("/api/health-activities?date=2026-10-11", dailyRequest.path)
        assertEquals("Bearer token-1", dailyRequest.getHeader("Authorization"))
    }

    @Test
    fun myCharacter_returnsNullBeforeFirstSync() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.myCharacter())
    }

    @Test
    fun unauthorized_invalidatesTheTokenThatWasSent() = runTest {
        server.enqueue(json("""{"status":401,"message":"인증이 필요합니다."}""", 401))
        try {
            client.myCharacter()
            fail("expected Unauthorized")
        } catch (e: ApiException.Unauthorized) {
            assertEquals("인증이 필요합니다.", e.serverMessage)
        }
        assertEquals(listOf("token-1"), tokens.rejected)
        assertNull(tokens.token)
    }

    @Test
    fun unauthorizedOnLogin_doesNotTouchTheSession() = runTest {
        server.enqueue(json("""{"message":"x"}""", 401))
        runCatching { client.logIn("a", "b") }
        assertTrue(tokens.rejected.isEmpty())
    }

    @Test
    fun protectedCall_withoutToken_failsWithoutNetwork() = runTest {
        tokens.token = null
        try {
            client.dailyActivities("2026-10-11")
            fail("expected Unauthorized")
        } catch (_: ApiException.Unauthorized) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rateLimited_exposesRetryAfterSeconds_andServerMessage() = runTest {
        server.enqueue(json("""{"message":"요청이 너무 많아요. 42초 후에 다시 시도해 주세요."}""", 429).setHeader("Retry-After", "42"))
        try {
            client.logIn("sprout", "password123")
            fail("expected RateLimited")
        } catch (e: ApiException.RateLimited) {
            assertEquals(42L, e.retryAfterSeconds)
            assertEquals("요청이 너무 많아요. 42초 후에 다시 시도해 주세요.", e.serverMessage)
        }
        assertTrue(tokens.rejected.isEmpty())
    }

    @Test
    fun retryAfter_parsing() {
        assertEquals(5L, ApiClient.parseRetryAfter(" 5 "))
        assertNull(ApiClient.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ApiClient.parseRetryAfter(null))
        assertNull(ApiClient.parseRetryAfter("-1"))
    }

    @Test
    fun serverError_carriesStatusAndMessage_evenWithoutJsonBody() = runTest {
        server.enqueue(json("""{"message":"아이디 또는 비밀번호가 올바르지 않습니다."}""", 400))
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))
        val first = runCatching { client.logIn("a", "password123") }.exceptionOrNull() as ApiException.Server
        assertEquals(400, first.status)
        assertEquals("아이디 또는 비밀번호가 올바르지 않습니다.", first.serverMessage)
        val second = runCatching { client.logIn("a", "password123") }.exceptionOrNull() as ApiException.Server
        assertEquals(503, second.status)
        assertNull(second.serverMessage)
    }

    @Test
    fun connectionFailure_isNetworkError() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val error = runCatching { client.logIn("a", "password123") }.exceptionOrNull()
        assertTrue(error.toString(), error is ApiException.Network)
    }

    @Test
    fun malformedSuccessBody_isInvalidResponse() = runTest {
        server.enqueue(json("""{"unexpected":true}"""))
        val error = runCatching { client.logIn("a", "password123") }.exceptionOrNull()
        assertTrue(error.toString(), error is ApiException.InvalidResponse)
        assertFalse(error is ApiException.Network)
    }
}
