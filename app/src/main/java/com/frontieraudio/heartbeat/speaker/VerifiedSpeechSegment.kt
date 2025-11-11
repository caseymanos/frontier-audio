package com.frontieraudio.heartbeat.speaker

import com.frontieraudio.heartbeat.SpeechSegment

data class VerifiedSpeechSegment(
    val segment: SpeechSegment,
    val similarity: Float,
    val scores: FloatArray,
    val verifiedAtMillis: Long,
)
