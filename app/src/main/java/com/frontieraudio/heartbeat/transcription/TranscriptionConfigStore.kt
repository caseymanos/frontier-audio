package com.frontieraudio.heartbeat.transcription

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.transcriptionConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "transcription_config"
)

class TranscriptionConfigStore(private val context: Context) {

    companion object {
        private val CARTESIA_API_KEY = stringPreferencesKey("cartesia_api_key")
        private val CARTESIA_URL = stringPreferencesKey("cartesia_url")
        private val LANGUAGE = stringPreferencesKey("language")
        private val INCLUDE_TIMESTAMPS = stringPreferencesKey("include_timestamps")
        private val ENABLE_LOCATION_TRACKING = stringPreferencesKey("enable_location_tracking")
    }

    val configFlow: Flow<TranscriptionConfig> = context.transcriptionConfigDataStore.data.map { preferences ->
        TranscriptionConfig(
            cartesiaApiKey = preferences[CARTESIA_API_KEY] ?: "",
            cartesiaUrl = preferences[CARTESIA_URL] ?: "wss://api.cartesia.ai/stt/websocket",
            language = preferences[LANGUAGE] ?: "en",
            includeTimestamps = preferences[INCLUDE_TIMESTAMPS] != "false",
            enableLocationTracking = preferences[ENABLE_LOCATION_TRACKING] != "false"
        )
    }

    suspend fun updateCartesiaApiKey(apiKey: String) {
        context.transcriptionConfigDataStore.edit { preferences ->
            preferences[CARTESIA_API_KEY] = apiKey
        }
    }

    suspend fun updateCartesiaUrl(url: String) {
        context.transcriptionConfigDataStore.edit { preferences ->
            preferences[CARTESIA_URL] = url
        }
    }

    suspend fun updateLanguage(language: String) {
        context.transcriptionConfigDataStore.edit { preferences ->
            preferences[LANGUAGE] = language
        }
    }

    suspend fun updateIncludeTimestamps(include: Boolean) {
        context.transcriptionConfigDataStore.edit { preferences ->
            preferences[INCLUDE_TIMESTAMPS] = include.toString()
        }
    }

    suspend fun updateEnableLocationTracking(enable: Boolean) {
        context.transcriptionConfigDataStore.edit { preferences ->
            preferences[ENABLE_LOCATION_TRACKING] = enable.toString()
        }
    }
}
