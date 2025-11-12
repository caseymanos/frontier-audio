package com.frontieraudio.heartbeat.speaker

data class SpeakerVerificationConfig(
    val matchThreshold: Float = DEFAULT_MATCH_THRESHOLD,
    val positiveRetentionMillis: Long = DEFAULT_POSITIVE_RETENTION_MS,
    val negativeCooldownMillis: Long = DEFAULT_NEGATIVE_COOLDOWN_MS,
) {
    companion object {
        const val MIN_THRESHOLD = 0.01f
        const val MAX_THRESHOLD = 0.90f
        const val DEFAULT_MATCH_THRESHOLD = 0.01f
        const val DEFAULT_POSITIVE_RETENTION_MS = 5_000L
        const val DEFAULT_NEGATIVE_COOLDOWN_MS = 500L
    }
}
