package com.frontieraudio.heartbeat.transcription

import com.frontieraudio.heartbeat.speaker.VerifiedSpeechSegment
import com.frontieraudio.heartbeat.location.LocationData
import com.frontieraudio.heartbeat.metrics.TranscriptionMetrics

data class TranscriptionResult(
    val segment: VerifiedSpeechSegment,
    val transcript: String,
    val confidence: Float?,
    val isFinal: Boolean,
    val processingTimeMs: Long,
    val locationData: LocationData?,
    val metrics: TranscriptionMetrics? = null
)

data class TranscriptionConfig(
    val cartesiaApiKey: String,
    val cartesiaUrl: String = "wss://api.cartesia.ai/stt/websocket",
    val language: String = "en",
    val includeTimestamps: Boolean = true,
    val enableLocationTracking: Boolean = true
)
