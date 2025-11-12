package com.frontieraudio.heartbeat.speaker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class VoiceProfileStore(context: Context) {

    private val dataStore = context.voiceProfileDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    val voiceProfileFlow = MutableSharedFlow<VoiceProfile?>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        Log.d(TAG, "VoiceProfileStore initialized, starting DataStore collection")
        scope.launch {
            // Emit initial null so collectors don't block
            val emitted = voiceProfileFlow.tryEmit(null)
            Log.d(TAG, "Emitted initial null to SharedFlow: $emitted")
            
            // Give collectors time to subscribe
            kotlinx.coroutines.delay(100)
            Log.d(TAG, "Starting DataStore collection after delay")
            
            dataStore.data.collect { prefs ->
                Log.d(TAG, "voiceProfileFlow: DataStore emitted new data on thread ${Thread.currentThread().name}")
                val snapshot = mapOf(
                    VoiceProfile.KEY_ID to (prefs[stringPreferencesKey(VoiceProfile.KEY_ID)] ?: ""),
                    VoiceProfile.KEY_PROFILE_BYTES to (prefs[stringPreferencesKey(VoiceProfile.KEY_PROFILE_BYTES)] ?: run {
                        Log.d(TAG, "voiceProfileFlow: No profile bytes, emitting null")
                        voiceProfileFlow.tryEmit(null)
                        return@collect
                    }),
                    VoiceProfile.KEY_CREATED_AT to (prefs[stringPreferencesKey(VoiceProfile.KEY_CREATED_AT)] ?: run {
                        Log.d(TAG, "voiceProfileFlow: Missing created_at, emitting null")
                        voiceProfileFlow.tryEmit(null)
                        return@collect
                    }),
                    VoiceProfile.KEY_SAMPLE_RATE to (prefs[stringPreferencesKey(VoiceProfile.KEY_SAMPLE_RATE)] ?: run {
                        Log.d(TAG, "voiceProfileFlow: Missing sample_rate, emitting null")
                        voiceProfileFlow.tryEmit(null)
                        return@collect
                    }),
                    VoiceProfile.KEY_MIN_ENROLL_SAMPLES to (prefs[stringPreferencesKey(VoiceProfile.KEY_MIN_ENROLL_SAMPLES)] ?: run {
                        Log.d(TAG, "voiceProfileFlow: Missing min_enroll_samples, emitting null")
                        voiceProfileFlow.tryEmit(null)
                        return@collect
                    }),
                    VoiceProfile.KEY_ENGINE_VERSION to (prefs[stringPreferencesKey(VoiceProfile.KEY_ENGINE_VERSION)] ?: ""),
                    VoiceProfile.KEY_SAMPLE_COUNT to (prefs[stringPreferencesKey(VoiceProfile.KEY_SAMPLE_COUNT)] ?: "0")
                )
                val profile = VoiceProfile.fromPreferences(snapshot)
                Log.d(TAG, "voiceProfileFlow: Parsed profile: ${if (profile != null) "loaded (${profile.samplesCaptured} samples)" else "null"}, emitting to SharedFlow")
                Log.d(TAG, "voiceProfileFlow: Subscription count: ${voiceProfileFlow.subscriptionCount.value}")
                val emitted = voiceProfileFlow.tryEmit(profile)
                Log.d(TAG, "voiceProfileFlow: SharedFlow emitted: $emitted, replay cache size: ${voiceProfileFlow.replayCache.size}")
            }
        }
    }

    suspend fun getVoiceProfile(): VoiceProfile? = voiceProfileFlow.firstOrNull()

    suspend fun saveVoiceProfile(profile: VoiceProfile) {
        Log.d(TAG, "saveVoiceProfile: Saving profile with ${profile.samplesCaptured} samples")
        dataStore.edit { prefs ->
            clearExisting(prefs)
            profile.toPreferenceMap().forEach { (key, value) ->
                prefs[stringPreferencesKey(key)] = value
            }
        }
        Log.d(TAG, "saveVoiceProfile: Profile saved successfully")
    }

    companion object {
        private const val TAG = "VoiceProfileStore"
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
