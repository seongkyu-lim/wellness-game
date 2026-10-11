package com.example.wellnessgame.auth

import android.util.Log
import com.example.wellnessgame.data.network.AccessTokenSource
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.data.network.RefreshResponse
import com.example.wellnessgame.data.network.TokenRefresh
import com.example.wellnessgame.domain.LoginProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/** 화면에 보여 줄 로그인 상태(토큰은 담지 않는다). */
data class SessionState(
    /** 저장소에서 세션 복원을 마쳤는지. */
    val restored: Boolean = false,
    val userId: String? = null,
    val displayName: String? = null,
    val provider: LoginProvider? = null,
    /** 세션이 강제로 끝났을 때(401·만료) 재로그인 안내를 보여 준다. */
    val reloginRequired: Boolean = false,
) {
    val isSignedIn: Boolean get() = userId != null
}

/**
 * 로그인 상태와 토큰을 관리한다(iOS `UserSession`·`SessionCredentialStore`).
 * - 토큰은 [TokenStore](Keystore 암호화)에만 영속하고, 메모리 사본으로 요청 헤더를 만든다.
 * - 401을 받으면 그 요청에 실었던 토큰이 지금 토큰과 같을 때만 세션을 지운다(요청 중 다른 계정으로 바뀐 경우 보호).
 * - Auth v2: 액세스 토큰이 곧 만료되거나 401이면 리프레시 토큰으로 갱신한다. 갱신은 한 번에 하나만 보내고
 *   (같은 리프레시 토큰을 두 번 보내면 서버가 재사용으로 보고 family를 폐기할 수 있음), 기다리던 요청은
 *   그 결과(이미 갱신된 토큰)를 그대로 쓴다. 자세한 규칙은 [refreshSingleFlight].
 */
class SessionManager(
    private val store: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
    /** 갱신 본문을 돌리는 앱 수명 스코프. 호출자 취소와 무관하게 회전 결과를 저장해야 한다. */
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AccessTokenSource {
    /** 세션 상태·저장소 변경을 직렬화한다. */
    private val mutex = Mutex()

    /** 진행 중 갱신([inflight]) 확인·시작을 직렬화한다. 잡는 순서는 항상 [refreshMutex] → [mutex]. */
    private val refreshMutex = Mutex()

    /** 진행 중이거나 마지막으로 끝난 갱신. [refreshMutex] 안에서만 읽고 쓴다. */
    private var inflight: Deferred<String?>? = null
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile
    private var session: StoredSession? = null

    suspend fun restore() = mutex.withLock {
        val stored = store.load()
        if (stored != null && !stored.isUsable(now())) {
            store.clear()
            session = null
            _state.value = SessionState(restored = true, reloginRequired = true)
        } else {
            session = stored
            _state.value = stored.toState()
        }
    }

    suspend fun signIn(response: AuthTokenResponse, provider: LoginProvider) = mutex.withLock {
        val now = now()
        val refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
        val stored = StoredSession(
            userId = response.userId,
            displayName = response.displayName,
            provider = provider.storageKey,
            accessToken = response.accessToken,
            expiresAtEpochSeconds = expiry(response.expiresIn, now),
            refreshToken = refreshToken,
            refreshExpiresAtEpochSeconds = refreshToken?.let { expiry(response.refreshExpiresIn, now) },
        )
        store.save(stored)
        session = stored
        _state.value = stored.toState()
    }

    /** 로컬 세션을 지운다. 서버 로그아웃에 쓰도록 지운 세션의 리프레시 토큰을 돌려준다(없으면 null). */
    suspend fun signOut(): String? = mutex.withLock {
        val ended = session
        session = null
        store.clear()
        _state.value = SessionState(restored = true)
        ended?.refreshToken
    }

    fun dismissReloginNotice() = _state.update { it.copy(reloginRequired = false) }

    override suspend fun tokenForRequest(refresh: TokenRefresh): String? {
        val current = session ?: return null
        if (!current.accessNeedsRefresh(now())) return current.accessToken
        return try {
            refreshSingleFlight(rejectedToken = null, refresh = refresh)
        } catch (e: ApiException) {
            // 일시적 실패(네트워크·429·5xx·응답 해석 실패·저장 실패): 아직 유효한 토큰이면 그대로 쓰고 다음 요청에서 다시 갱신한다.
            if (current.isAccessExpired(now())) throw e
            current.accessToken
        }
    }

    override suspend fun renewAfterUnauthorized(rejectedToken: String, refresh: TokenRefresh): String? =
        refreshSingleFlight(rejectedToken, refresh)

    override suspend fun onUnauthorized(rejectedToken: String) = expire(rejectedToken)

    /**
     * 새 액세스 토큰을 돌려준다. [rejectedToken]은 서버가 401로 거절한 토큰이고, null이면 만료 임박에 따른 사전 갱신이다.
     * - 다른 요청이 이미 갱신했으면 다시 보내지 않고 그 토큰을 쓴다. 갱신 중이면 그 결과를 함께 기다린다.
     * - 갱신 본문(요청·응답 저장)은 앱 수명 [refreshScope]의 공유 [Deferred]로 돈다. 기다리던 호출자가 취소돼도 갱신은
     *   끝까지 가서 회전 결과를 저장한다(서버는 이미 회전했으므로 결과를 잃으면 30초 뒤 이전 토큰이 재사용으로 판정돼
     *   family 폐기와 함께 다른 기기 액세스 토큰까지 무효가 된다).
     * - 서버가 리프레시를 거절하면(401·400) 세션을 끝내고 null. 그 밖의 [ApiException](네트워크·429·5xx·응답 해석 실패·
     *   회전 결과 저장 실패)은 일시 실패로 보고 세션을 그대로 둔 채 던진다.
     */
    private suspend fun refreshSingleFlight(rejectedToken: String?, refresh: TokenRefresh): String? {
        val job = refreshMutex.withLock {
            inflight?.takeIf { it.isActive } ?: run {
                val current = session ?: return null
                val now = now()
                val rejected = rejectedToken != null && current.accessToken == rejectedToken
                if (!rejected && !current.accessNeedsRefresh(now)) return current.accessToken

                val refreshToken = current.usableRefreshToken(now)
                if (refreshToken == null) {
                    // 리프레시 토큰이 없는 이전 형식 세션이거나 리프레시도 만료됨: 거절되지 않았고 아직 유효하면 그대로 쓴다.
                    if (!rejected && !current.isAccessExpired(now)) return current.accessToken
                    expire(current.accessToken)
                    return null
                }
                refreshScope.async { performRefresh(current, refreshToken, refresh) }.also { inflight = it }
            }
        }
        return job.await()
    }

    private suspend fun performRefresh(current: StoredSession, refreshToken: String, refresh: TokenRefresh): String? {
        val response = try {
            refresh.refresh(refreshToken)
        } catch (e: ApiException) {
            if (!e.isRefreshRejected()) throw e
            expire(current.accessToken)
            return null
        }
        return applyRotation(current, response)
    }

    /**
     * 회전 응답을 저장하고 세션을 바꾼다. 갱신 중 로그아웃·다른 로그인이 있었으면 그 상태를 덮어쓰지 않고 null
     * (다른 계정 토큰으로 원 요청을 다시 보내지 않도록).
     * 저장을 [SAVE_ATTEMPTS]번까지 시도하고, 끝내 실패하면 메모리·디스크 모두 이전 세션(이전 리프레시 토큰)을 그대로 두고
     * 일시 실패([ApiException.Network])로 던진다. 다음 요청이 서버 유예 구간(30초) 안에 이전 토큰으로 다시 갱신해 복구한다.
     * 저장하지 못한 새 토큰으로 세션을 이어 가면, 프로세스가 끝난 뒤 디스크의 이전 토큰이 유예 구간 밖에서 재사용돼
     * 모든 기기 세션이 무효가 될 수 있으므로 쓰지 않는다.
     */
    private suspend fun applyRotation(current: StoredSession, response: RefreshResponse): String? = mutex.withLock {
        if (session !== current) return@withLock null
        val now = now()
        val updated = current.copy(
            accessToken = response.accessToken,
            expiresAtEpochSeconds = expiry(response.expiresIn, now),
            refreshToken = response.refreshToken,
            refreshExpiresAtEpochSeconds = expiry(response.refreshExpiresIn, now),
        )
        var lastError: Exception? = null
        repeat(SAVE_ATTEMPTS) {
            try {
                store.save(updated)
                session = updated
                return@withLock updated.accessToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        Log.w(TAG, "Could not persist rotated session; keeping the previous one", lastError)
        throw ApiException.Network(checkNotNull(lastError))
    }

    /**
     * [token]이 지금 세션의 토큰일 때만 세션을 끝낸다. 비교·메모리 정리·저장소 삭제를 한 잠금 안에서 해서
     * 로그인([signIn])과 겹쳐도 늦게 온 401이 새로 저장된 세션을 지우지 않는다.
     */
    private suspend fun expire(token: String) = mutex.withLock {
        if (session?.accessToken != token) return@withLock
        session = null
        store.clear()
        _state.value = SessionState(restored = true, reloginRequired = true)
    }

    private fun now(): Long = clock.instant().epochSecond

    private fun expiry(seconds: Long?, now: Long): Long? = seconds?.takeIf { it > 0 }?.let { now + it }

    private fun StoredSession.isAccessExpired(now: Long): Boolean =
        expiresAtEpochSeconds?.let { now >= it } ?: false

    /** 만료 시각을 모르면 미리 갱신하지 않는다(401에서 갱신). */
    private fun StoredSession.accessNeedsRefresh(now: Long): Boolean =
        expiresAtEpochSeconds?.let { it - now <= REFRESH_LEEWAY_SECONDS } ?: false

    /** 리프레시 토큰이 있고 아직 만료되지 않았으면 그 값. 만료 시각을 모르면 서버 판단에 맡긴다. */
    private fun StoredSession.usableRefreshToken(now: Long): String? =
        refreshToken?.takeIf { refreshExpiresAtEpochSeconds?.let { now < it } ?: true }

    /** 서버가 리프레시 토큰을 받아들이지 않음(없음·만료·폐기·재사용 401, 빈 값 400). 세션을 끝낸다. */
    private fun ApiException.isRefreshRejected(): Boolean =
        this is ApiException.Unauthorized || (this is ApiException.Server && status == 400)

    private fun StoredSession.isUsable(now: Long): Boolean =
        usableRefreshToken(now) != null || !isAccessExpired(now)

    private fun StoredSession?.toState() = SessionState(
        restored = true,
        userId = this?.userId,
        displayName = this?.displayName,
        provider = LoginProvider.fromStorageKey(this?.provider),
    )

    companion object {
        /** 액세스 토큰이 이 시간(초) 안에 만료되면 요청 전에 미리 갱신한다(iOS·웹과 같은 60초). */
        const val REFRESH_LEEWAY_SECONDS = 60L

        /** 회전 결과 저장 시도 횟수(첫 시도 포함). */
        private const val SAVE_ATTEMPTS = 3
        private const val TAG = "SessionManager"
    }
}
