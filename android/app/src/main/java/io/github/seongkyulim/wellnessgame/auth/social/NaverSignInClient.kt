package io.github.seongkyulim.wellnessgame.auth.social

import android.content.Context
import io.github.seongkyulim.wellnessgame.domain.LoginProvider
import com.navercorp.nid.NidOAuth
import com.navercorp.nid.core.data.errorcode.NidOAuthErrorCode
import com.navercorp.nid.oauth.util.NidOAuthCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 네이버 로그인. 서버가 토큰 대체 공격을 막도록 access·refresh 토큰을 함께 넘긴다. */
class NaverSignInClient(
    context: Context,
    private val clientId: String,
    private val clientSecret: String,
    private val clientName: String,
) : SocialSignInClient {
    override val provider = LoginProvider.NAVER
    override val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && clientName.isNotBlank()

    init {
        if (isConfigured) NidOAuth.initialize(context.applicationContext, clientId, clientSecret, clientName)
    }

    override suspend fun signIn(host: SignInHost): SocialCredential {
        check(isConfigured) { "NAVER_CLIENT_ID/SECRET/NAME is not set" }
        val activity = host.activity()
        suspendCancellableCoroutine { cont ->
            NidOAuth.requestLogin(activity, object : NidOAuthCallback {
                override fun onSuccess() = cont.resume(Unit)

                override fun onFailure(errorCode: String, errorDesc: String) {
                    val error = if (NidOAuth.getLastErrorCode() == NidOAuthErrorCode.CLIENT_USER_CANCEL) {
                        SocialSignInCancelled()
                    } else {
                        SocialSignInFailed("$errorCode: $errorDesc")
                    }
                    cont.resumeWithException(error)
                }
            })
        }
        val accessToken = NidOAuth.getAccessToken()
        val refreshToken = NidOAuth.getRefreshToken()
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            throw SocialSignInFailed("Empty Naver token")
        }
        return SocialCredential.Naver(accessToken, refreshToken)
    }

    override suspend fun signOut() {
        if (!isConfigured) return
        suspendCancellableCoroutine { cont ->
            NidOAuth.logout(object : NidOAuthCallback {
                override fun onSuccess() = cont.resume(Unit)
                override fun onFailure(errorCode: String, errorDesc: String) = cont.resume(Unit)
            })
        }
    }
}
