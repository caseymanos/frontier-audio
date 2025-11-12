package com.frontieraudio.heartbeat.diagnostics

import com.frontieraudio.heartbeat.SpeechSegment
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

object SegmentDiagnosticsAnalyzer {

    data class Metrics(
        val rmsDbfs: Float,
        val peakDbfs: Float,
        val clippingRatio: Float,
        val speechRatio: Float,
        val warnings: List<String>
    )

    private const val MIN_DB = -120f
    private const val CLIP_THRESHOLD = 0.99f
    private const val SPEECH_THRESHOLD = 0.02f
    private const val QUIET_DB_THRESHOLD = -42f
    private const val SILENCE_RATIO_THRESHOLD = 0.6f
    private const val CLIP_RATIO_THRESHOLD = 0.02f

    fun analyze(segment: SpeechSegment): Metrics {
        val samples = segment.samples
        if (samples.isEmpty()) {
            return Metrics(
                rmsDbfs = MIN_DB,
                peakDbfs = MIN_DB,
                clippingRatio = 0f,
                speechRatio = 0f,
                warnings = listOf("Segment contained no audio samples")
            )
        }
        var sumSquares = 0.0
        var speechCount = 0
        var clipCount = 0
        var maxAmplitude = 0f
        samples.forEach { sample ->
            val normalized = abs(sample.toInt()) / MAX_SHORT
            sumSquares += normalized * normalized
            if (normalized >= SPEECH_THRESHOLD) {
                speechCount++
            }
            if (normalized >= CLIP_THRESHOLD) {
                clipCount++
            }
            maxAmplitude = max(maxAmplitude, normalized)
        }
        val total = samples.size.toFloat()
        val rms = sqrt(sumSquares / total)
        val rmsDbfs = toDb(rms)
        val peakDbfs = toDb(maxAmplitude)
        val clippingRatio = if (total > 0f) clipCount / total else 0f
        val speechRatio = if (total > 0f) speechCount / total else 0f
        val warnings = buildList {
            if (rmsDbfs < QUIET_DB_THRESHOLD) {
                add("Signal level low (${formatDb(rmsDbfs)})")
            }
            if (clippingRatio > CLIP_RATIO_THRESHOLD) {
                add("Clipping detected (${formatPercent(clippingRatio)})")
            }
            if (speechRatio < SILENCE_RATIO_THRESHOLD) {
                add("Speech ratio low (${formatPercent(speechRatio)})")
            }
        }
        return Metrics(
            rmsDbfs = rmsDbfs,
            peakDbfs = peakDbfs,
            clippingRatio = clippingRatio,
            speechRatio = speechRatio,
            warnings = warnings
        )
    }

    private fun toDb(value: Double): Float {
        if (value <= 0.0) return MIN_DB
        return (20.0 * ln(value) / LN_10).toFloat().coerceIn(MIN_DB, 0f)
    }

    private fun toDb(value: Float): Float = toDb(value.toDouble())

    private fun formatDb(db: Float): String = String.format("%.1f dBFS", db)

    private fun formatPercent(ratio: Float): String = String.format("%.1f%%", ratio * 100f)

    private const val MAX_SHORT = Short.MAX_VALUE.toFloat()
    private val LN_10 = ln(10.0)
}
