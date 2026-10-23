package io.github.seongkyulim.wellnessgame.auth

import io.github.seongkyulim.wellnessgame.auth.social.KakaoSignInClient
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class KeystoreErrorPolicyTest {
    @Test
    fun permanentDecryptionOrFormatErrors_clearTheSession() {
        assertTrue(KeystoreTokenStore.isUnrecoverable(AEADBadTagException("tag mismatch")))
        assertTrue(KeystoreTokenStore.isUnrecoverable(UnrecoverableKeyException("gone")))
        assertTrue(KeystoreTokenStore.isUnrecoverable(SerializationException("bad json")))
        assertTrue(KeystoreTokenStore.isUnrecoverable(KeystoreTokenStore.MalformedSessionException()))
        assertTrue(KeystoreTokenStore.isUnrecoverable(IllegalArgumentException("bad base64")))
    }

    @Test
    fun transientKeystoreErrors_keepTheSession() {
        assertFalse(KeystoreTokenStore.isUnrecoverable(KeyStoreException("keystore busy")))
        assertFalse(KeystoreTokenStore.isUnrecoverable(ProviderException("hal")))
        assertFalse(KeystoreTokenStore.isUnrecoverable(IOException("disk")))
        assertFalse(KeystoreTokenStore.isUnrecoverable(IllegalStateException("locked")))
    }
}

class KakaoFallbackPolicyTest {
    @Test
    fun cancellationStops_otherClientErrorsFallBackToAccountLogin() {
        assertFalse(KakaoSignInClient.shouldFallBackToAccountLogin(ClientError(ClientErrorCause.Cancelled, "user cancelled")))
        assertTrue(KakaoSignInClient.shouldFallBackToAccountLogin(ClientError(ClientErrorCause.NotSupported, "KakaoTalk not installed")))
        assertTrue(KakaoSignInClient.shouldFallBackToAccountLogin(ClientError(ClientErrorCause.IllegalState, "not connected")))
    }

    @Test
    fun serverOrUnknownErrors_doNotFallBack() {
        assertFalse(KakaoSignInClient.shouldFallBackToAccountLogin(IOException("network")))
        assertFalse(KakaoSignInClient.shouldFallBackToAccountLogin(null))
    }
}
