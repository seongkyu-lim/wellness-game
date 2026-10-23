package io.github.seongkyulim.wellnessgame.ui.login

import io.github.seongkyulim.wellnessgame.auth.AuthRepository
import io.github.seongkyulim.wellnessgame.auth.SessionManager
import io.github.seongkyulim.wellnessgame.auth.social.SocialCredential
import io.github.seongkyulim.wellnessgame.auth.social.SocialSignInCancelled
import io.github.seongkyulim.wellnessgame.auth.social.SocialSignInFailed
import io.github.seongkyulim.wellnessgame.domain.LoginProvider
import io.github.seongkyulim.wellnessgame.testing.AUTH_BODY
import io.github.seongkyulim.wellnessgame.testing.FakeSocialClient
import io.github.seongkyulim.wellnessgame.testing.apiClient
import io.github.seongkyulim.wellnessgame.testing.FakeTokenStore
import io.github.seongkyulim.wellnessgame.testing.MainDispatcherRule
import io.github.seongkyulim.wellnessgame.testing.NoHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private val google = FakeSocialClient(LoginProvider.GOOGLE)
    private val kakao = FakeSocialClient(LoginProvider.KAKAO, result = { SocialCredential.Kakao("kakao-access") })
    private val naver = FakeSocialClient(LoginProvider.NAVER, isConfigured = false)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    /** 로그인 요청은 OkHttp 스레드에서 실제 소켓으로 가므로 가상 시간이 아니라 상태 변화를 기다린다. */
    private suspend fun LoginViewModel.awaitIdle() = withContext(Dispatchers.Default) {
        withTimeout(5_000) { state.first { !it.isBusy } }
    }

    private val authBody = AUTH_BODY

    private fun TestScope.fixture(region: String? = "US"): Pair<LoginViewModel, SessionManager> {
        val session = SessionManager(FakeTokenStore())
        val api = server.apiClient(session) { "en" }
        val auth = AuthRepository(
            api,
            session,
            mapOf(LoginProvider.GOOGLE to google, LoginProvider.KAKAO to kakao, LoginProvider.NAVER to naver),
            backgroundScope,
        )
        return LoginViewModel(auth, region) to session
    }

    @Test
    fun policyAndConfiguredProviders_comeFromRegionAndKeys() = runTest {
        val (vm, _) = fixture(region = "KR")
        assertEquals(listOf(LoginProvider.KAKAO, LoginProvider.NAVER, LoginProvider.GOOGLE), vm.state.value.policy.primary)
        assertEquals(setOf(LoginProvider.GOOGLE, LoginProvider.KAKAO), vm.state.value.configured)
    }

    @Test
    fun credentials_requireUsernameAndEightCharacterPassword() = runTest {
        val (vm, _) = fixture()
        assertFalse(vm.state.value.canSubmitCredentials)
        vm.onUsernameChange("sprout")
        vm.onPasswordChange("short")
        assertFalse(vm.state.value.canSubmitCredentials)
        vm.onPasswordChange("long-enough")
        assertTrue(vm.state.value.canSubmitCredentials)
        vm.onUsernameChange("   ")
        assertFalse(vm.state.value.canSubmitCredentials)
    }

    @Test
    fun logIn_success_signsInAndClearsPassword() = runTest {
        server.enqueue(MockResponse().setBody(authBody))
        val (vm, session) = fixture()
        vm.onUsernameChange("sprout")
        vm.onPasswordChange("password123")
        vm.logIn()
        vm.awaitIdle()
        assertEquals("u-1", session.state.value.userId)
        assertEquals(LoginProvider.PASSWORD, session.state.value.provider)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun logIn_failure_showsServerMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Wrong username or password."}"""))
        val (vm, session) = fixture()
        vm.onUsernameChange("sprout")
        vm.onPasswordChange("password123")
        vm.logIn()
        vm.awaitIdle()
        assertEquals(LoginError.Server("Wrong username or password."), vm.state.value.error)
        assertEquals("password123", vm.state.value.password)
        assertFalse(session.state.value.isSignedIn)
    }

    @Test
    fun signUp_rateLimited_showsRetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17").setBody("{}"))
        val (vm, _) = fixture()
        vm.onUsernameChange("sprout")
        vm.onPasswordChange("password123")
        vm.signUp()
        vm.awaitIdle()
        assertEquals(LoginError.RateLimited(17), vm.state.value.error)
        assertEquals("/api/auth/signup", server.takeRequest().path)
    }

    @Test
    fun socialSignIn_exchangesSdkTokenWithServer() = runTest {
        server.enqueue(MockResponse().setBody(authBody))
        val (vm, session) = fixture()
        vm.socialSignIn(LoginProvider.KAKAO, NoHost)
        vm.awaitIdle()
        assertEquals(LoginProvider.KAKAO, session.state.value.provider)
        val request = server.takeRequest()
        assertEquals("/api/auth/kakao/native", request.path)
        assertEquals("""{"accessToken":"kakao-access"}""", request.body.readUtf8())
    }

    @Test
    fun socialSignIn_cancelled_isSilent() = runTest {
        google.result = { throw SocialSignInCancelled() }
        val (vm, session) = fixture()
        vm.socialSignIn(LoginProvider.GOOGLE, NoHost)
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(session.state.value.isSignedIn)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun socialSignIn_sdkFailure_showsProviderError() = runTest {
        google.result = { throw SocialSignInFailed("boom") }
        val (vm, _) = fixture()
        vm.socialSignIn(LoginProvider.GOOGLE, NoHost)
        advanceUntilIdle()
        assertEquals(LoginError.Social(LoginProvider.GOOGLE), vm.state.value.error)
    }

    @Test
    fun unconfiguredProvider_isIgnored() = runTest {
        val (vm, _) = fixture()
        vm.socialSignIn(LoginProvider.NAVER, NoHost)
        advanceUntilIdle()
        assertEquals(0, naver.signInCount)
        assertFalse(vm.state.value.isBusy)
    }
}
