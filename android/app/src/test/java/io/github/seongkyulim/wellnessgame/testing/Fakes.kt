package com.example.wellnessgame.testing

import com.example.wellnessgame.auth.StoredSession
import com.example.wellnessgame.auth.TokenStore
import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.auth.social.SocialCredential
import com.example.wellnessgame.auth.social.SocialSignInClient
import com.example.wellnessgame.data.network.AccessTokenSource
import com.example.wellnessgame.data.network.TokenRefresh
import com.example.wellnessgame.data.settings.AppSettings
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.health.HealthAvailability
import com.example.wellnessgame.data.health.HealthDataProvider
import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.CharacterState
import com.example.wellnessgame.data.network.NetworkModule
import com.example.wellnessgame.domain.DailyQuestProgress
import com.example.wellnessgame.sync.DashboardRepository
import com.example.wellnessgame.sync.HealthAccess
import com.example.wellnessgame.sync.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** 메모리 토큰 저장소. */
class FakeTokenStore(var stored: StoredSession? = null) : TokenStore {
    var saveCount = 0
    var clearCount = 0

    /** 값이 있으면 save가 이 게이트가 열릴 때까지 멈춘다(동시성 테스트용). */
    var saveGate: CompletableDeferred<Unit>? = null

    /** 값이 있으면 save가 이 예외로 실패한다(저장값은 그대로). */
    var saveFailure: Exception? = null
    override suspend fun load() = stored
    override suspend fun save(session: StoredSession) {
        saveCount++
        saveGate?.await()
        saveFailure?.let { throw it }
        stored = session
    }
    override suspend fun clear() {
        clearCount++
        stored = null
    }
}

/** 리프레시를 모르는 단순 토큰 출처. 401이면 갱신 없이 세션을 끝낸다. */
class FakeTokenSource(var token: String? = "token-1") : AccessTokenSource {
    val rejected = mutableListOf<String>()
    override suspend fun tokenForRequest(refresh: TokenRefresh) = token
    override suspend fun renewAfterUnauthorized(rejectedToken: String, refresh: TokenRefresh): String? {
        onUnauthorized(rejectedToken)
        return null
    }
    override suspend fun onUnauthorized(rejectedToken: String) {
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

// MARK: - 서버 픽스처

/** 로그인·회원가입·소셜 교환의 공통 성공 응답. */
const val AUTH_BODY =
    """{"userId":"u-1","displayName":"Leaf","accessToken":"jwt","tokenType":"Bearer","expiresIn":3600}"""

const val CHARACTER_JSON =
    """{"level":3,"currentXp":40,"totalXp":240,"nextLevelXp":150,"stats":{"str":1,"vit":2,"intStat":3,"discipline":4,"recovery":5}}"""

fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** 이 MockWebServer를 바라보는 실제 [ApiClient]. */
fun MockWebServer.apiClient(tokens: AccessTokenSource, language: () -> String = { "ko" }): ApiClient =
    ApiClient(NetworkModule.api(url("/").toString(), NetworkModule.okHttpClient(language)), tokens)

// MARK: - 대시보드

class FakeDashboardRepository(
    val snapshot: DailyHealthSnapshot,
    var syncResult: suspend () -> SyncResult,
    var access: HealthAccess = HealthAccess.GRANTED,
    var mock: Boolean = false,
    var character: CharacterState? = null,
    var quests: suspend (LocalDate) -> DailyQuestProgress = { DailyQuestProgress(9_000, 10, 400) },
) : DashboardRepository {
    var syncCount = 0
    var characterCount = 0
    val questDates = mutableListOf<LocalDate>()
    override suspend fun healthAccess() = access
    override suspend fun isUsingMockData() = mock
    override suspend fun permissionsToRequest() = setOf("perm")
    override suspend fun loadSnapshot() = snapshot
    override suspend fun syncToday(): SyncResult {
        syncCount++
        return syncResult()
    }
    override suspend fun character(): CharacterState? {
        characterCount++
        return character
    }
    override suspend fun todayQuests(date: LocalDate): DailyQuestProgress {
        questDates += date
        return quests(date)
    }
}

/** 고정된 스냅샷을 돌려주는 건강 데이터 출처. */
class FakeHealthProvider(
    var snapshot: DailyHealthSnapshot,
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var granted: Boolean = true,
) : HealthDataProvider {
    var loadCount = 0
    override val requiredPermissions = setOf("read")
    override suspend fun availability() = availability
    override suspend fun hasPermissions() = granted
    override suspend fun loadToday(): DailyHealthSnapshot {
        loadCount++
        return snapshot
    }
}
