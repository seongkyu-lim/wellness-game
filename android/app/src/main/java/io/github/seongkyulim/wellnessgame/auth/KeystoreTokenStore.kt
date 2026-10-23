package io.github.seongkyulim.wellnessgame.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.seongkyulim.wellnessgame.data.network.ApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * 세션을 JSON으로 직렬화한 뒤 Android Keystore의 AES-256-GCM 키로 암호화해 DataStore에 둔다.
 * 키는 기기 밖으로 나가지 않으며(백업 제외), 복호화에 실패하면(키 삭제·기기 이전 등) 세션을 지우고 로그아웃 상태로 둔다.
 * deprecated된 androidx.security-crypto 대신 Keystore + javax.crypto를 직접 쓴다.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val dataStore = context.applicationContext.sessionDataStore

    // Keystore 암호화·DataStore 입출력은 메인 스레드를 막지 않도록 IO 디스패처에서 한다.
    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        val blob = dataStore.data.first()[KEY_BLOB] ?: return@withContext null
        try {
            ApiJson.decodeFromString(StoredSession.serializer(), decrypt(blob))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isUnrecoverable(e)) {
                // 키 손상·교체, 형식 변경: 다시 읽어도 복구할 수 없으므로 지우고 다시 로그인하게 한다.
                Log.w(TAG, "Stored session is unrecoverable; clearing it", e)
                clear()
            } else {
                // KeyStoreException 등 일시적 오류: 세션은 남겨 두고 이번에만 로그아웃 상태로 본다.
                Log.w(TAG, "Stored session could not be read right now; keeping it", e)
            }
            null
        }
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        val blob = encrypt(ApiJson.encodeToString(StoredSession.serializer(), session))
        dataStore.edit { it[KEY_BLOB] = blob }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dataStore.edit { it.remove(KEY_BLOB) }
        Unit
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val ivLength = bytes.first().toInt()
        if (ivLength <= 0 || ivLength >= bytes.size) throw MalformedSessionException()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** 저장된 값이 깨져 있음(IV 길이 이상). */
    class MalformedSessionException : IllegalArgumentException("Malformed session blob")

    companion object {
        /**
         * 다시 시도해도 복호화할 수 없는 오류인지. 이때만 세션을 지운다.
         * - 인증 태그 불일치(키가 바뀜·데이터 변조), 복구할 수 없는 키, 영구 무효화된 키
         * - 저장 형식 오류(Base64·JSON·IV 길이)
         */
        fun isUnrecoverable(error: Throwable): Boolean = when (error) {
            is AEADBadTagException, is UnrecoverableKeyException, is KeyPermanentlyInvalidatedException -> true
            // SerializationException·MalformedSessionException·Base64 디코딩 실패는 모두 IllegalArgumentException이다.
            is IllegalArgumentException -> true
            else -> false
        }

        private const val TAG = "KeystoreTokenStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wellness_session_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val KEY_BLOB = stringPreferencesKey("session_blob")
    }
}
