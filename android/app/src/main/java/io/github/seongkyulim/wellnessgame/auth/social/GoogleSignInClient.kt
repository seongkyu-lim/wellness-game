package com.example.wellnessgame.auth.social

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.wellnessgame.domain.LoginProvider
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Credential Manager의 "Sign in with Google" 버튼 흐름으로 ID 토큰을 받는다.
 * [serverClientId]는 서버가 aud로 검증하는 웹 OAuth 클라이언트 ID다.
 */
class GoogleSignInClient(
    context: Context,
    private val serverClientId: String,
) : SocialSignInClient {
    private val appContext = context.applicationContext
    private val credentialManager by lazy { CredentialManager.create(appContext) }

    override val provider = LoginProvider.GOOGLE
    override val isConfigured: Boolean get() = serverClientId.isNotBlank()

    override suspend fun signIn(host: SignInHost): SocialCredential {
        check(isConfigured) { "GOOGLE_SERVER_CLIENT_ID is not set" }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        val result = try {
            credentialManager.getCredential(host.activity(), request)
        } catch (e: GetCredentialCancellationException) {
            throw SocialSignInCancelled()
        } catch (e: NoCredentialException) {
            // 기기에 Google 계정이 없음: 사용자가 계정을 추가한 뒤 다시 시도해야 한다.
            throw SocialSignInFailed(e.message, e)
        } catch (e: GetCredentialException) {
            throw SocialSignInFailed(e.message, e)
        }
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw SocialSignInFailed("Unexpected credential type")
        }
        return try {
            SocialCredential.Google(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } catch (e: GoogleIdTokenParsingException) {
            throw SocialSignInFailed(e.message, e)
        }
    }

    override suspend fun signOut() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}
