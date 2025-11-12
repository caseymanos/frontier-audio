package com.frontieraudio.heartbeat

import android.app.Application
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfigStore
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
import com.frontieraudio.heartbeat.transcription.TranscriptionConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HeartbeatApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val voiceProfileStore by lazy { VoiceProfileStore(applicationContext) }
    val configStore by lazy { SpeakerVerificationConfigStore(applicationContext) }
    val transcriptionConfigStore by lazy { TranscriptionConfigStore(applicationContext) }

    companion object {
        private var instance: HeartbeatApplication? = null

        fun getInstance(): HeartbeatApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Cartesia API key from BuildConfig if available
        if (BuildConfig.CARTESIA_API_KEY.isNotBlank()) {
            applicationScope.launch {
                transcriptionConfigStore.updateCartesiaApiKey(BuildConfig.CARTESIA_API_KEY)
            }
        }
    }
}
