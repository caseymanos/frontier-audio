package com.frontieraudio.heartbeat.speaker

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Persistent representation of the enrolled user's Eagle voice profile payload.
 */
data class VoiceProfile(
    val id: String = UUID.randomUUID().toString(),
    val profileBytes: ByteArray,
    val createdAtMillis: Long,
    val sampleRateHz: Int,
    val minEnrollSamples: Int,
    val engineVersion: String,
    val samplesCaptured: Int,
) {

    fun toPreferenceMap(): Map<String, String> {
        return mapOf(
            KEY_ID to id,
            KEY_PROFILE_BYTES to profileBytes.encodeToBase64(),
            KEY_CREATED_AT to createdAtMillis.toString(),
            KEY_SAMPLE_RATE to sampleRateHz.toString(),
            KEY_MIN_ENROLL_SAMPLES to minEnrollSamples.toString(),
            KEY_ENGINE_VERSION to engineVersion,
            KEY_SAMPLE_COUNT to samplesCaptured.toString()
        )
    }

    companion object {
        const val KEY_PREFIX = "voice_profile_"
        const val KEY_ID = "${KEY_PREFIX}id"
        const val KEY_PROFILE_BYTES = "${KEY_PREFIX}data"
        const val KEY_CREATED_AT = "${KEY_PREFIX}created_at"
        const val KEY_SAMPLE_RATE = "${KEY_PREFIX}sample_rate"
        const val KEY_MIN_ENROLL_SAMPLES = "${KEY_PREFIX}min_enroll_samples"
        const val KEY_ENGINE_VERSION = "${KEY_PREFIX}engine_version"
        const val KEY_SAMPLE_COUNT = "${KEY_PREFIX}sample_count"

        fun fromPreferences(prefs: Map<String, String>): VoiceProfile? {
            val profileData = prefs[KEY_PROFILE_BYTES]?.decodeBase64ToByteArray() ?: return null
            val createdAt = prefs[KEY_CREATED_AT]?.toLongOrNull() ?: return null
            val sampleRate = prefs[KEY_SAMPLE_RATE]?.toIntOrNull() ?: return null
            val minEnrollSamples = prefs[KEY_MIN_ENROLL_SAMPLES]?.toIntOrNull() ?: return null
            val engineVersion = prefs[KEY_ENGINE_VERSION] ?: ""
            val sampleCount = prefs[KEY_SAMPLE_COUNT]?.toIntOrNull() ?: 0
            val id = prefs[KEY_ID] ?: UUID.randomUUID().toString()
            return VoiceProfile(
                id = id,
                profileBytes = profileData,
                createdAtMillis = createdAt,
                sampleRateHz = sampleRate,
                minEnrollSamples = minEnrollSamples,
                engineVersion = engineVersion,
                samplesCaptured = sampleCount
            )
        }
    }
}

private fun ByteArray.encodeToBase64(): String {
    return Base64.encodeToString(this, Base64.NO_WRAP)
}

private fun String.decodeBase64ToByteArray(): ByteArray? {
    return try {
        Base64.decode(this, Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }
}
