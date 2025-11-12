package com.frontieraudio.heartbeat.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

object AudioEffectUtils {

    data class EffectStatus(
        val name: String,
        val enabled: Boolean
    )

    private const val TAG = "AudioEffectUtils"

    fun detectEnabledEffects(record: AudioRecord): List<String> {
        val sessionId = record.audioSessionId
        if (sessionId == AudioRecord.ERROR_BAD_VALUE || sessionId == AudioRecord.ERROR) {
            Log.w(TAG, "Invalid audio session id; skipping audio effect detection")
            return emptyList()
        }
        val effects = mutableListOf<String>()
        checkEffect(
            isAvailable = AutomaticGainControl.isAvailable(),
            name = "Automatic Gain Control",
            sessionId = sessionId,
            create = { AutomaticGainControl.create(sessionId) }
        )?.let { if (it.enabled) effects.add(it.name) }
        checkEffect(
            isAvailable = NoiseSuppressor.isAvailable(),
            name = "Noise Suppressor",
            sessionId = sessionId,
            create = { NoiseSuppressor.create(sessionId) }
        )?.let { if (it.enabled) effects.add(it.name) }
        checkEffect(
            isAvailable = AcousticEchoCanceler.isAvailable(),
            name = "Acoustic Echo Canceler",
            sessionId = sessionId,
            create = { AcousticEchoCanceler.create(sessionId) }
        )?.let { if (it.enabled) effects.add(it.name) }
        return effects.distinct()
    }

    private inline fun <reified T> checkEffect(
        isAvailable: Boolean,
        name: String,
        sessionId: Int,
        create: () -> T?
    ): EffectStatus? where T : android.media.audiofx.AudioEffect {
        if (!isAvailable) return null
        return try {
            val effect = create() ?: return null
            try {
                EffectStatus(name, effect.enabled)
            } finally {
                try {
                    effect.release()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inspect $name for session $sessionId", t)
            null
        }
    }
}
