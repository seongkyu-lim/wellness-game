package com.example.wellnessgame.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.wellnessgame.data.network.ApiJson
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException
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

    override suspend fun load(): StoredSession? {
        val blob = dataStore.data.first()[KEY_BLOB] ?: return null
        return try {
            ApiJson.decodeFromString(StoredSession.serializer(), decrypt(blob))
        } catch (e: Exception) {
            // 키 손상·형식 변경: 복구할 수 없으므로 지우고 다시 로그인하게 한다.
            Log.w(TAG, "Stored session could not be decrypted; clearing it", e)
            clear()
            null
        }
    }

    override suspend fun save(session: StoredSession) {
        val blob = encrypt(ApiJson.encodeToString(StoredSession.serializer(), session))
        dataStore.edit { it[KEY_BLOB] = blob }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_BLOB) }
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
        if (ivLength <= 0 || ivLength >= bytes.size) throw GeneralSecurityException("Malformed session blob")
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

    private companion object {
        const val TAG = "KeystoreTokenStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wellness_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val KEY_BLOB = stringPreferencesKey("session_blob")
    }
}
