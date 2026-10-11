package com.example.wellnessgame.auth

import com.example.wellnessgame.data.network.AccessTokenSource
import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * 로그인 상태와 토큰을 관리한다(iOS `UserSession`).
 * - 토큰은 [TokenStore](Keystore 암호화)에만 영속하고, 메모리 사본으로 요청 헤더를 만든다.
 * - 401을 받으면 그 요청에 실었던 토큰이 지금 토큰과 같을 때만 세션을 지운다(요청 중 다른 계정으로 바뀐 경우 보호).
 */
class SessionManager(
    private val store: TokenStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenSource {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile
    private var session: StoredSession? = null

    suspend fun restore() = mutex.withLock {
        val stored = store.load()
        if (stored != null && stored.isExpired()) {
            store.clear()
            session = null
            _state.value = SessionState(restored = true, reloginRequired = true)
        } else {
            session = stored
            _state.value = stored.toState()
        }
    }

    suspend fun signIn(response: AuthTokenResponse, provider: LoginProvider) = mutex.withLock {
        val stored = StoredSession(
            userId = response.userId,
            displayName = response.displayName,
            provider = provider.storageKey,
            accessToken = response.accessToken,
            expiresAtEpochSeconds = response.expiresIn?.takeIf { it > 0 }?.let { clock.instant().epochSecond + it },
        )
        store.save(stored)
        session = stored
        _state.value = stored.toState()
    }

    suspend fun signOut() = mutex.withLock {
        session = null
        store.clear()
        _state.value = SessionState(restored = true)
    }

    fun dismissReloginNotice() = _state.update { it.copy(reloginRequired = false) }

    override fun currentToken(): String? {
        val current = session ?: return null
        if (current.isExpired()) {
            expire(current.accessToken)
            return null
        }
        return current.accessToken
    }

    override fun onUnauthorized(rejectedToken: String) = expire(rejectedToken)

    private fun expire(token: String) {
        if (session?.accessToken != token) return
        session = null
        _state.value = SessionState(restored = true, reloginRequired = true)
        scope.launch {
            mutex.withLock {
                // 그사이 다시 로그인했다면 새 세션을 지우지 않는다.
                if (session == null) store.clear()
            }
        }
    }

    private fun StoredSession.isExpired(): Boolean =
        expiresAtEpochSeconds?.let { clock.instant().epochSecond >= it } ?: false

    private fun StoredSession?.toState() = SessionState(
        restored = true,
        userId = this?.userId,
        displayName = this?.displayName,
        provider = LoginProvider.fromStorageKey(this?.provider),
    )
}
