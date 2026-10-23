package com.example.wellnessgame.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 민감하지 않은 앱 설정(Mock 데이터 토글, 마지막 동기화 시각). */
interface AppSettings {
    val useMockData: Flow<Boolean>
    val lastSyncedAt: Flow<Instant?>
    suspend fun setUseMockData(enabled: Boolean)
    suspend fun setLastSyncedAt(instant: Instant?)
}

class SettingsStore(context: Context) : AppSettings {
    private val dataStore = context.applicationContext.settingsDataStore

    override val useMockData: Flow<Boolean> = dataStore.data.map { it[USE_MOCK] ?: false }
    override val lastSyncedAt: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[LAST_SYNCED_AT]?.let(Instant::ofEpochMilli)
    }

    override suspend fun setUseMockData(enabled: Boolean) {
        dataStore.edit { it[USE_MOCK] = enabled }
    }

    override suspend fun setLastSyncedAt(instant: Instant?) {
        dataStore.edit { prefs ->
            if (instant == null) prefs.remove(LAST_SYNCED_AT) else prefs[LAST_SYNCED_AT] = instant.toEpochMilli()
        }
    }

    private companion object {
        val USE_MOCK = booleanPreferencesKey("use_mock_data")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }
}
