package com.example.wellnessgame.auth

import android.util.Log
import com.example.wellnessgame.data.network.AccessTokenSource
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.data.network.RefreshResponse
import com.example.wellnessgame.data.network.TokenRefresh
import com.example.wellnessgame.domain.LoginProvider
import kotlinx.coroutines.CancellationException
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
 * - Auth v2: 액세스 토큰이 곧 만료되거나 401이면 리프레시 토큰으로 갱신한다. 갱신은 [refreshMutex]로 한 번에 하나만
 *   보내고(같은 리프레시 토큰을 두 번 보내면 서버가 재사용으로 보고 family를 폐기할 수 있음), 기다리던 요청은
 *   이미 갱신된 토큰을 그대로 쓴다.
 */
class SessionManager(
    private val store: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenSource {
    /** 세션 상태·저장소 변경을 직렬화한다. */
    private val mutex = Mutex()

    /** 리프레시 단일 비행. 잡는 순서는 항상 [refreshMutex] → [mutex]. */
    private val refreshMutex = Mutex()
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
        val stored = StoredSession(
            userId = response.userId,
            displayName = response.displayName,
            provider = provider.storageKey,
            accessToken = response.accessToken,
            expiresAtEpochSeconds = expiry(response.expiresIn, now),
            refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
            refreshExpiresAtEpochSeconds = response.refreshToken?.takeIf { it.isNotBlank() }
                ?.let { expiry(response.refreshExpiresIn, now) },
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
        val now = now()
        if (!current.accessNeedsRefresh(now)) return current.accessToken
        if (current.usableRefreshToken(now) == null) {
            if (!current.isAccessExpired(now)) return current.accessToken
            expire(current.accessToken)
            return null
        }
        return try {
            refreshSingleFlight(current.accessToken, refresh)
        } catch (e: ApiException) {
            // 일시적 실패(네트워크·429·5xx): 아직 유효한 토큰이면 그대로 쓰고 다음 요청에서 다시 갱신한다.
            if (current.isAccessExpired(now())) throw e
            current.accessToken
        }
    }

    override suspend fun renewAfterUnauthorized(rejectedToken: String, refresh: TokenRefresh): String? =
        refreshSingleFlight(rejectedToken, refresh)

    override suspend fun onUnauthorized(rejectedToken: String) = expire(rejectedToken)

    /**
     * 새 액세스 토큰을 돌려준다. 다른 요청이 이미 갱신했으면 다시 보내지 않고 그 토큰을 쓴다.
     * 서버가 리프레시를 거절하면(401·400) 세션을 끝내고 null. 일시적 실패는 [ApiException]으로 던지고 세션은 둔다.
     */
    private suspend fun refreshSingleFlight(rejectedToken: String, refresh: TokenRefresh): String? =
        refreshMutex.withLock {
            val current = session ?: return@withLock null
            val now = now()
            val stale = current.accessToken == rejectedToken || current.accessNeedsRefresh(now)
            if (!stale) return@withLock current.accessToken

            val refreshToken = current.usableRefreshToken(now)
            if (refreshToken == null) {
                // 리프레시 토큰이 없는 이전 형식 세션이거나 리프레시도 만료됨.
                if (current.accessToken != rejectedToken && !current.isAccessExpired(now)) {
                    return@withLock current.accessToken
                }
                expire(current.accessToken)
                return@withLock null
            }

            val response = try {
                refresh.refresh(refreshToken)
            } catch (e: ApiException.Unauthorized) {
                expire(current.accessToken)
                return@withLock null
            } catch (e: ApiException.Server) {
                if (e.status != 400) throw e
                expire(current.accessToken)
                return@withLock null
            }
            applyRotation(current, response)
        }

    /**
     * 회전 응답으로 세션을 바꾼다. 갱신 중 로그아웃·다른 로그인이 있었으면 그 상태를 덮어쓰지 않는다.
     * 저장이 실패해도 저장소의 이전 리프레시 토큰은 지우지 않는다. 메모리에는 새 토큰을 써서 이 프로세스는 계속 동작하고,
     * 다음 회전 때 다시 저장한다. 그 전에 프로세스가 끝나면 이전 토큰이 서버 유예 구간(30초) 안에서 복구에 쓰인다.
     */
    private suspend fun applyRotation(current: StoredSession, response: RefreshResponse): String? = mutex.withLock {
        if (session !== current) return@withLock session?.accessToken
        val now = now()
        val updated = current.copy(
            accessToken = response.accessToken,
            expiresAtEpochSeconds = expiry(response.expiresIn, now),
            refreshToken = response.refreshToken,
            refreshExpiresAtEpochSeconds = expiry(response.refreshExpiresIn, now),
        )
        try {
            store.save(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist rotated session; keeping the previous one on disk", e)
        }
        session = updated
        updated.accessToken
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
        private const val TAG = "SessionManager"
    }
}
