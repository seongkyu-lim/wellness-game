package com.example.wellnessgame.auth.social

import android.content.Context
import com.example.wellnessgame.domain.LoginProvider
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 카카오 로그인. 카카오톡이 있으면 앱으로, 없거나 실패하면 카카오계정(웹)으로 로그인한다. */
class KakaoSignInClient(
    context: Context,
    private val nativeAppKey: String,
) : SocialSignInClient {
    override val provider = LoginProvider.KAKAO
    override val isConfigured: Boolean get() = nativeAppKey.isNotBlank()

    init {
        if (isConfigured) KakaoSdk.init(context.applicationContext, nativeAppKey)
    }

    override suspend fun signIn(host: SignInHost): SocialCredential {
        check(isConfigured) { "KAKAO_NATIVE_APP_KEY is not set" }
        val activity = host.activity()
        val client = UserApiClient.instance
        val token = if (client.isKakaoTalkLoginAvailable(activity)) {
            try {
                login { callback -> client.loginWithKakaoTalk(activity, callback = callback) }
            } catch (e: SocialSignInCancelled) {
                throw e
            } catch (e: SocialSignInFailed) {
                // 카카오톡에 연결된 계정이 없는 등: 카카오계정 로그인으로 이어서 시도한다.
                login { callback -> client.loginWithKakaoAccount(activity, callback = callback) }
            }
        } else {
            login { callback -> client.loginWithKakaoAccount(activity, callback = callback) }
        }
        return SocialCredential.Kakao(token.accessToken)
    }

    override suspend fun signOut() {
        if (!isConfigured) return
        suspendCancellableCoroutine { cont -> UserApiClient.instance.logout { cont.resume(Unit) } }
    }

    private suspend fun login(
        start: ((OAuthToken?, Throwable?) -> Unit) -> Unit,
    ): OAuthToken = suspendCancellableCoroutine { cont ->
        start { token, error ->
            when {
                error is ClientError && error.reason == ClientErrorCause.Cancelled ->
                    cont.resumeWithException(SocialSignInCancelled())
                error != null -> cont.resumeWithException(SocialSignInFailed(error.message, error))
                token != null -> cont.resume(token)
                else -> cont.resumeWithException(SocialSignInFailed("Empty Kakao token"))
            }
        }
    }
}
