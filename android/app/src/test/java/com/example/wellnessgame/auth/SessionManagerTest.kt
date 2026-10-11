package com.example.wellnessgame.auth

import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.testing.FakeTokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionManagerTest {
    private val now = Instant.parse("2026-10-11T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun TestScope.manager(store: FakeTokenStore) = SessionManager(store, this, clock)

    private fun stored(token: String = "jwt", expiresAt: Long? = now.epochSecond + 60) =
        StoredSession("u-1", "새싹", "kakao", token, expiresAt)

    @Test
    fun restore_loadsSavedSession_withoutExposingToken() = runTest {
        val session = manager(FakeTokenStore(stored()))
        session.restore()
        val state = session.state.value
        assertTrue(state.restored)
        assertEquals("u-1", state.userId)
        assertEquals(LoginProvider.KAKAO, state.provider)
        assertEquals("jwt", session.currentToken())
    }

    @Test
    fun restore_expiredSession_clearsItAndAsksToLogInAgain() = runTest {
        val store = FakeTokenStore(stored(expiresAt = now.epochSecond - 1))
        val session = manager(store)
        session.restore()
        assertFalse(session.state.value.isSignedIn)
        assertTrue(session.state.value.reloginRequired)
        assertNull(store.stored)
        assertNull(session.currentToken())
    }

    @Test
    fun signIn_persistsServerTokenAndExpiry() = runTest {
        val store = FakeTokenStore()
        val session = manager(store)
        session.restore()
        session.signIn(AuthTokenResponse("u-9", "Leaf", "new-jwt", "Bearer", 3600), LoginProvider.PASSWORD)

        assertEquals(StoredSession("u-9", "Leaf", "password", "new-jwt", now.epochSecond + 3600), store.stored)
        assertEquals("u-9", session.state.value.userId)
        assertEquals("new-jwt", session.currentToken())
    }

    @Test
    fun unauthorized_forCurrentToken_clearsSession() = runTest {
        val store = FakeTokenStore(stored())
        val session = manager(store)
        session.restore()
        session.onUnauthorized("jwt")
        advanceUntilIdle()
        assertFalse(session.state.value.isSignedIn)
        assertTrue(session.state.value.reloginRequired)
        assertNull(store.stored)
    }

    @Test
    fun lateUnauthorized_forOldToken_keepsNewSession() = runTest {
        val store = FakeTokenStore(stored(token = "old"))
        val session = manager(store)
        session.restore()
        session.signIn(AuthTokenResponse("u-2", null, "new", null, null), LoginProvider.GOOGLE)
        session.onUnauthorized("old")
        advanceUntilIdle()
        assertEquals("u-2", session.state.value.userId)
        assertEquals("new", store.stored?.accessToken)
        assertNull(store.stored?.expiresAtEpochSeconds)
    }

    @Test
    fun signOut_clearsStore() = runTest {
        val store = FakeTokenStore(stored())
        val session = manager(store)
        session.restore()
        session.signOut()
        assertNull(store.stored)
        assertNull(session.currentToken())
        assertFalse(session.state.value.reloginRequired)
    }

    @Test
    fun lateUnauthorized_racingWithSignIn_neverWipesTheNewSession() = runTest {
        val store = FakeTokenStore(stored(token = "old"))
        val session = manager(store)
        session.restore()

        // 새 로그인이 저장 도중(잠금을 쥔 채) 멈춰 있는 사이에 이전 토큰의 401이 도착한다.
        val gate = CompletableDeferred<Unit>().also { store.saveGate = it }
        val signIn = launch {
            session.signIn(AuthTokenResponse("u-2", null, "new", null, null), LoginProvider.GOOGLE)
        }
        runCurrent()
        val unauthorized = launch { session.onUnauthorized("old") }
        runCurrent()
        assertEquals("401 must wait for the in-flight sign-in", "u-1", session.state.value.userId)

        gate.complete(Unit)
        signIn.join()
        unauthorized.join()

        assertEquals("u-2", session.state.value.userId)
        assertFalse(session.state.value.reloginRequired)
        assertEquals("new", store.stored?.accessToken)
        assertEquals(0, store.clearCount)
        assertEquals("new", session.currentToken())
    }

    @Test
    fun unauthorized_beforeSignInTakesLock_clearsOnlyTheOldSession() = runTest {
        val store = FakeTokenStore(stored(token = "old"))
        val session = manager(store)
        session.restore()
        val unauthorized = launch { session.onUnauthorized("old") }
        val signIn = launch {
            session.signIn(AuthTokenResponse("u-2", null, "new", null, null), LoginProvider.GOOGLE)
        }
        unauthorized.join()
        signIn.join()
        assertEquals(1, store.clearCount)
        assertEquals("u-2", session.state.value.userId)
        assertEquals("new", store.stored?.accessToken)
    }

    @Test
    fun tokenExpiringAfterRestore_isNotReturned_andSessionIsCleared() = runTest {
        var current = now
        val clock = object : Clock() {
            override fun instant(): Instant = current
            override fun getZone(): ZoneOffset = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val store = FakeTokenStore(stored(expiresAt = now.epochSecond + 60))
        val session = SessionManager(store, this, clock)
        session.restore()
        assertEquals("jwt", session.currentToken())

        current = now.plusSeconds(61)
        assertNull(session.currentToken())
        advanceUntilIdle()
        assertTrue(session.state.value.reloginRequired)
        assertNull(store.stored)
    }
}
