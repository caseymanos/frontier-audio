package com.frontieraudio.heartbeat.metrics

import android.os.SystemClock
import java.util.Locale

/**
 * Comprehensive metrics for tracking a single transcription event through the entire pipeline
 */
data class TranscriptionMetrics(
    val transcriptionId: Long,
    val vadStartMs: Long,
    val vadEndMs: Long,
    val stage2StartMs: Long,
    val stage2EndMs: Long,
    val stage3StartMs: Long,
    val stage3FirstPartialMs: Long?,
    val stage3FinalMs: Long,
    val audioDurationMs: Long,
    val sampleCount: Int,
    val transcript: String,
    val wordCount: Int,
    val verificationSimilarity: Float?,
    val verificationThreshold: Float?,
    val verificationPassed: Boolean,
    val isBypassMode: Boolean = false
) {
    // Calculated metrics
    val vadLatencyMs: Long get() = vadEndMs - vadStartMs
    val stage2LatencyMs: Long get() = stage2EndMs - stage2StartMs
    val stage3LatencyMs: Long get() = stage3FinalMs - stage3StartMs
    val totalLatencyMs: Long get() = stage3FinalMs - vadStartMs
    val realTimeFactor: Float get() = if (audioDurationMs > 0) totalLatencyMs.toFloat() / audioDurationMs else 0f
    val wordsPerSecond: Float get() = if (audioDurationMs > 0) (wordCount * 1000f) / audioDurationMs else 0f

    fun formatSummary(): String = buildString {
        appendLine("=== Metrics #$transcriptionId ===")
        appendLine("📝 \"$transcript\" ($wordCount words)")
        appendLine("⏱️  Audio: ${formatMs(audioDurationMs)}")
        appendLine("   VAD:    ${formatMs(vadLatencyMs)}")
        appendLine("   Stage2: ${formatMs(stage2LatencyMs)}")
        appendLine("   Stage3: ${formatMs(stage3LatencyMs)}")
        stage3FirstPartialMs?.let { appendLine("   1st partial: ${formatMs(it - stage3StartMs)}") }
        appendLine("   TOTAL:  ${formatMs(totalLatencyMs)}")
        appendLine("📊 RTF: ${String.format(Locale.US, "%.2f", realTimeFactor)}x")
        appendLine("   Rate: ${String.format(Locale.US, "%.1f", wordsPerSecond)} words/sec")
        if (!isBypassMode) {
            appendLine(
                "🔐 Similarity: ${
                    String.format(
                        Locale.US,
                        "%.3f",
                        verificationSimilarity ?: 0f
                    )
                } (${if (verificationPassed) "PASS" else "FAIL"})"
            )
        }
    }

    companion object {
        private fun formatMs(ms: Long) = if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.2fs", ms / 1000.0)
    }
}

/**
 * Builder for creating TranscriptionMetrics
 */
class MetricsBuilder(val id: Long) {
    var vadStartMs = 0L
    var vadEndMs = 0L
    var stage2StartMs = 0L
    var stage2EndMs = 0L
    var stage3StartMs = 0L
    var stage3FirstPartialMs: Long? = null
    var stage3FinalMs = 0L
    var audioDurationMs = 0L
    var sampleCount = 0
    var transcript = ""
    var wordCount = 0
    var verificationSimilarity: Float? = null
    var verificationThreshold: Float? = null
    var verificationPassed = false
    var isBypassMode = false

    fun build() = TranscriptionMetrics(
        id, vadStartMs, vadEndMs, stage2StartMs, stage2EndMs,
        stage3StartMs, stage3FirstPartialMs, stage3FinalMs,
        audioDurationMs, sampleCount, transcript, wordCount,
        verificationSimilarity, verificationThreshold,
        verificationPassed, isBypassMode
    )
}

/**
 * Aggregate statistics across multiple transcriptions
 */
data class AggregateStats(
    val count: Int,
    val totalAudioMs: Long,
    val avgLatencyMs: Double,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val avgRTF: Double,
    val avgVadMs: Double,
    val avgStage2Ms: Double,
    val avgStage3Ms: Double,
    val totalWords: Int,
    val passRate: Float
) {
    fun formatSummary() = buildString {
        appendLine("=== Session Stats ===")
        appendLine("Count: $count")
        appendLine("Audio: ${formatMs(totalAudioMs)}")
        appendLine(
            "Avg Latency: ${formatMs(avgLatencyMs.toLong())} (RTF: ${
                String.format(
                    Locale.US,
                    "%.2f",
                    avgRTF
                )
            }x)"
        )
        appendLine("Range: ${formatMs(minLatencyMs)} - ${formatMs(maxLatencyMs)}")
        appendLine()
        appendLine("Stage Breakdown (Avg):")
        appendLine("  VAD:    ${formatMs(avgVadMs.toLong())}")
        appendLine("  Stage2: ${formatMs(avgStage2Ms.toLong())}")
        appendLine("  Stage3: ${formatMs(avgStage3Ms.toLong())}")
        appendLine()
        appendLine("Words: $totalWords")
        appendLine("Pass Rate: ${String.format(Locale.US, "%.1f", passRate * 100)}%")
    }

    companion object {
        private fun formatMs(ms: Long) = if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.2fs", ms / 1000.0)
    }
}
