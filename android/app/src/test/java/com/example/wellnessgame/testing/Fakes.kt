package com.example.wellnessgame.testing

import com.example.wellnessgame.auth.StoredSession
import com.example.wellnessgame.auth.TokenStore
import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.auth.social.SocialCredential
import com.example.wellnessgame.auth.social.SocialSignInClient
import com.example.wellnessgame.data.network.AccessTokenSource
import com.example.wellnessgame.data.settings.AppSettings
import com.example.wellnessgame.domain.LoginProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** 메모리 토큰 저장소. */
class FakeTokenStore(var stored: StoredSession? = null) : TokenStore {
    var saveCount = 0
    var clearCount = 0
    override suspend fun load() = stored
    override suspend fun save(session: StoredSession) {
        saveCount++
        stored = session
    }
    override suspend fun clear() {
        clearCount++
        stored = null
    }
}

class FakeTokenSource(var token: String? = "token-1") : AccessTokenSource {
    val rejected = mutableListOf<String>()
    override fun currentToken() = token
    override fun onUnauthorized(rejectedToken: String) {
        rejected += rejectedToken
        if (token == rejectedToken) token = null
    }
}

class FakeSettings(mock: Boolean = false, last: Instant? = null) : AppSettings {
    val mockFlow = MutableStateFlow(mock)
    val lastFlow = MutableStateFlow(last)
    override val useMockData: Flow<Boolean> = mockFlow
    override val lastSyncedAt: Flow<Instant?> = lastFlow
    override suspend fun setUseMockData(enabled: Boolean) { mockFlow.value = enabled }
    override suspend fun setLastSyncedAt(instant: Instant?) { lastFlow.value = instant }
}

class FakeSocialClient(
    override val provider: LoginProvider,
    override val isConfigured: Boolean = true,
    var result: () -> SocialCredential = { SocialCredential.Google("id-token") },
) : SocialSignInClient {
    var signInCount = 0
    var signOutCount = 0
    override suspend fun signIn(host: SignInHost): SocialCredential {
        signInCount++
        return result()
    }
    override suspend fun signOut() { signOutCount++ }
}

/** SDK 화면을 띄우지 않는 가짜에는 Activity가 필요 없다. */
val NoHost = SignInHost { error("Fake clients never need an Activity") }
