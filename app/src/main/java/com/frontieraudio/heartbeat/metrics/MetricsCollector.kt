package com.frontieraudio.heartbeat.metrics

import android.os.SystemClock
import com.frontieraudio.heartbeat.debug.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects transcription metrics across the pipeline
 */
class MetricsCollector {
    private val idGen = AtomicLong(0)
    private val active = ConcurrentHashMap<Long, MetricsBuilder>()
    private val completed = mutableListOf<TranscriptionMetrics>()

    private val flowInternal = MutableSharedFlow<TranscriptionMetrics>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val metricsFlow: SharedFlow<TranscriptionMetrics> = flowInternal.asSharedFlow()

    fun startTracking(segmentId: Long): Long {
        val id = idGen.incrementAndGet()
        val builder = MetricsBuilder(id)
        builder.vadStartMs = SystemClock.elapsedRealtime()
        active[segmentId] = builder
        AppLogger.i(TAG, "📊 Started metrics tracking: segment=$segmentId, metrics_id=$id")
        return id
    }

    fun markVadComplete(segmentId: Long, audioMs: Long, samples: Int) {
        active[segmentId]?.apply {
            vadEndMs = SystemClock.elapsedRealtime()
            audioDurationMs = audioMs
            sampleCount = samples
            AppLogger.d(TAG, "📊 Segment $segmentId: VAD complete (${vadEndMs - vadStartMs}ms, audio=${audioMs}ms)")
        }
    }

    fun markStage2Start(segmentId: Long) {
        active[segmentId]?.apply {
            stage2StartMs = SystemClock.elapsedRealtime()
            AppLogger.d(TAG, "📊 Segment $segmentId: Stage2 start")
        } ?: AppLogger.w(TAG, "⚠️ Cannot mark Stage2 start: no active metrics for segment $segmentId")
    }

    fun markStage2Complete(
        segmentId: Long,
        similarity: Float?,
        threshold: Float?,
        passed: Boolean,
        bypass: Boolean = false
    ) {
        active[segmentId]?.apply {
            stage2EndMs = SystemClock.elapsedRealtime()
            verificationSimilarity = similarity
            verificationThreshold = threshold
            verificationPassed = passed
            isBypassMode = bypass
            AppLogger.d(TAG, "📊 Segment $segmentId: Stage2 complete (${stage2EndMs - stage2StartMs}ms, passed=$passed)")
        } ?: AppLogger.w(TAG, "⚠️ Cannot mark Stage2 complete: no active metrics for segment $segmentId")
    }

    fun markStage3Start(segmentId: Long) {
        active[segmentId]?.apply {
            stage3StartMs = SystemClock.elapsedRealtime()
            AppLogger.i(TAG, "📊 Segment $segmentId: Stage3 start")
        } ?: AppLogger.w(TAG, "⚠️ Cannot mark Stage3 start: no active metrics for segment $segmentId")
    }

    fun markFirstPartial(segmentId: Long) {
        active[segmentId]?.apply {
            if (stage3FirstPartialMs == null) {
                stage3FirstPartialMs = SystemClock.elapsedRealtime()
                AppLogger.d(
                    TAG,
                    "📊 Segment $segmentId: First partial received (${stage3FirstPartialMs!! - stage3StartMs}ms from Stage3 start)"
                )
            }
        } ?: AppLogger.w(TAG, "⚠️ Cannot mark first partial: no active metrics for segment $segmentId")
    }

    fun markFinalTranscript(segmentId: Long, text: String) {
        val builder = active.remove(segmentId)
        if (builder == null) {
            AppLogger.w(TAG, "No active metrics for segment $segmentId when marking final transcript")
            return
        }

        builder.stage3FinalMs = SystemClock.elapsedRealtime()
        builder.transcript = text
        builder.wordCount = countWords(text)

        val metrics = builder.build()
        synchronized(completed) {
            completed.add(metrics)
            if (completed.size > 100) completed.removeAt(0)
        }
        AppLogger.i(
            TAG,
            "📊 ✅ METRICS COMPLETE: id=${metrics.transcriptionId}, segment=$segmentId, latency=${metrics.totalLatencyMs}ms, RTF=${
                String.format(
                    "%.2f",
                    metrics.realTimeFactor
                )
            }x, words=${metrics.wordCount}"
        )
        flowInternal.tryEmit(metrics)
    }

    fun cancelTracking(segmentId: Long) {
        val wasActive = active.remove(segmentId) != null
        if (wasActive) {
            AppLogger.d(TAG, "📊 Cancelled metrics tracking for segment $segmentId")
        }
    }

    fun getCompleted(): List<TranscriptionMetrics> = synchronized(completed) { completed.toList() }

    fun getActiveTrackingCount(): Int = active.size

    fun getActiveSegmentIds(): Set<Long> = active.keys.toSet()

    fun getAggregateStats(): AggregateStats? {
        val list = getCompleted()
        if (list.isEmpty()) return null
        return AggregateStats(
            count = list.size,
            totalAudioMs = list.sumOf { it.audioDurationMs },
            avgLatencyMs = list.map { it.totalLatencyMs }.average(),
            minLatencyMs = list.minOf { it.totalLatencyMs },
            maxLatencyMs = list.maxOf { it.totalLatencyMs },
            avgRTF = list.map { it.realTimeFactor.toDouble() }.average(),
            avgVadMs = list.map { it.vadLatencyMs }.average(),
            avgStage2Ms = list.map { it.stage2LatencyMs }.average(),
            avgStage3Ms = list.map { it.stage3LatencyMs }.average(),
            totalWords = list.sumOf { it.wordCount },
            passRate = list.count { it.verificationPassed }.toFloat() / list.size
        )
    }

    fun clear() {
        val count = synchronized(completed) {
            val c = completed.size
            completed.clear()
            c
        }
        AppLogger.i(TAG, "📊 Cleared $count metrics")
    }

    fun exportCsv(): String = buildString {
        appendLine("ID,Timestamp,Total_Ms,Audio_Ms,VAD_Ms,Stage2_Ms,Stage3_Ms,Time_To_First_Partial_Ms,Words,RTF,Similarity,Threshold,Pass,Bypass,Transcript")
        getCompleted().forEach { m ->
            appendLine("${m.transcriptionId},${m.vadStartMs},${m.totalLatencyMs},${m.audioDurationMs},${m.vadLatencyMs},${m.stage2LatencyMs},${m.stage3LatencyMs},${m.stage3FirstPartialMs?.let { it - m.stage3StartMs } ?: ""},${m.wordCount},${
                String.format(
                    "%.2f",
                    m.realTimeFactor
                )
            },${m.verificationSimilarity ?: ""},${m.verificationThreshold ?: ""},${m.verificationPassed},${m.isBypassMode},\"${
                m.transcript.replace(
                    "\"",
                    "\"\""
                )
            }\"")
        }
    }

    private fun countWords(text: String) = text.trim().split(Regex("\\s+")).let { if (text.isBlank()) 0 else it.size }

    companion object {
        private const val TAG = "MetricsCollector"
    }
}
