package com.example.wellnessgame.auth

import com.example.wellnessgame.data.network.AuthTokenResponse
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.testing.FakeTokenStore
import kotlinx.coroutines.test.TestScope
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
}
