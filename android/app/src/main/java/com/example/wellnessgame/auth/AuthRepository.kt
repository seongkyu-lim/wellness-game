package com.example.wellnessgame.auth

import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.auth.social.SocialCredential
import com.example.wellnessgame.auth.social.SocialSignInClient
import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider

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

    /** 서버 세션을 지우고, 소셜 SDK에 남은 토큰도 정리한다(실패해도 로그아웃은 진행). */
    suspend fun signOut() {
        val provider = session.state.value.provider
        session.signOut()
        provider?.let { socialClients[it] }?.let { runCatching { it.signOut() } }
    }
}
