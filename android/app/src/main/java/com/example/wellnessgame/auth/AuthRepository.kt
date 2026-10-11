package com.example.wellnessgame.auth

import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.auth.social.SocialCredential
import com.example.wellnessgame.auth.social.SocialSignInClient
import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider
import kotlinx.coroutines.withTimeoutOrNull

/** 로그인 수단별로 자격 증명을 받아 서버 토큰으로 교환하고 세션에 저장한다. */
class AuthRepository(
    private val api: ApiClient,
    private val session: SessionManager,
    private val socialClients: Map<LoginProvider, SocialSignInClient>,
) {
    fun isConfigured(provider: LoginProvider): Boolean =
        provider == LoginProvider.PASSWORD || socialClients[provider]?.isConfigured == true

    suspend fun logIn(username: String, password: String) =
        session.signIn(api.logIn(username.trim(), password), LoginProvider.PASSWORD)

    suspend fun signUp(username: String, password: String) =
        session.signIn(api.signUp(username.trim(), password), LoginProvider.PASSWORD)

    suspend fun socialSignIn(provider: LoginProvider, host: SignInHost) {
        val client = requireNotNull(socialClients[provider]) { "No client for $provider" }
        val response: AuthTokenResponse = when (val credential = client.signIn(host)) {
            is SocialCredential.Google -> api.signInWithGoogle(credential.idToken)
            is SocialCredential.Kakao -> api.signInWithKakao(credential.accessToken)
            is SocialCredential.Naver -> api.signInWithNaver(credential.accessToken, credential.refreshToken)
        }
        session.signIn(response, provider)
    }

    /**
     * 로컬 세션을 먼저 지우고, 서버에 이 기기 리프레시 토큰 family 폐기를 요청한 뒤 소셜 SDK 토큰도 정리한다.
     * 서버·SDK 정리가 실패해도 로컬 로그아웃은 유지한다. 리프레시 토큰이 없는 이전 세션은 서버에 폐기할 것이 없다.
     */
    suspend fun signOut() {
        val provider = session.state.value.provider
        val refreshToken = session.signOut()
        if (refreshToken != null) {
            try {
                withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) { api.logOut(refreshToken) }
            } catch (_: ApiException) {
                // 서버가 폐기하지 못해도 토큰은 이 기기에서 지워졌고 만료(최대 30일)로 사라진다.
            }
        }
        provider?.let { socialClients[it] }?.let { runCatching { it.signOut() } }
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MILLIS = 5_000L
    }
}
