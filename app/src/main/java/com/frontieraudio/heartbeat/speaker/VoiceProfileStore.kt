package com.frontieraudio.heartbeat.speaker

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

class VoiceProfileStore(context: Context) {

    private val dataStore = context.voiceProfileDataStore

    val voiceProfileFlow: Flow<VoiceProfile?> = dataStore.data.map { prefs ->
        val snapshot = mapOf(
            VoiceProfile.KEY_ID to (prefs[stringPreferencesKey(VoiceProfile.KEY_ID)] ?: ""),
            VoiceProfile.KEY_PROFILE_BYTES to (prefs[stringPreferencesKey(VoiceProfile.KEY_PROFILE_BYTES)] ?: return@map null),
            VoiceProfile.KEY_CREATED_AT to (prefs[stringPreferencesKey(VoiceProfile.KEY_CREATED_AT)] ?: return@map null),
            VoiceProfile.KEY_SAMPLE_RATE to (prefs[stringPreferencesKey(VoiceProfile.KEY_SAMPLE_RATE)] ?: return@map null),
            VoiceProfile.KEY_MIN_ENROLL_SAMPLES to (prefs[stringPreferencesKey(VoiceProfile.KEY_MIN_ENROLL_SAMPLES)] ?: return@map null),
            VoiceProfile.KEY_ENGINE_VERSION to (prefs[stringPreferencesKey(VoiceProfile.KEY_ENGINE_VERSION)] ?: ""),
            VoiceProfile.KEY_SAMPLE_COUNT to (prefs[stringPreferencesKey(VoiceProfile.KEY_SAMPLE_COUNT)] ?: "0")
        )
        VoiceProfile.fromPreferences(snapshot)
    }

    suspend fun getVoiceProfile(): VoiceProfile? = voiceProfileFlow.firstOrNull()

    suspend fun saveVoiceProfile(profile: VoiceProfile) {
        dataStore.edit { prefs ->
            clearExisting(prefs)
            profile.toPreferenceMap().forEach { (key, value) ->
                prefs[stringPreferencesKey(key)] = value
            }
        }
    }

    suspend fun clearVoiceProfile() {
        dataStore.edit { prefs ->
            clearExisting(prefs)
        }
    }

    private fun clearExisting(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        listOf(
            VoiceProfile.KEY_ID,
            VoiceProfile.KEY_PROFILE_BYTES,
            VoiceProfile.KEY_CREATED_AT,
            VoiceProfile.KEY_SAMPLE_RATE,
            VoiceProfile.KEY_MIN_ENROLL_SAMPLES,
            VoiceProfile.KEY_ENGINE_VERSION,
            VoiceProfile.KEY_SAMPLE_COUNT
        ).forEach { keyName ->
            prefs.remove(stringPreferencesKey(keyName))
        }
    }
}
