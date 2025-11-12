package com.frontieraudio.heartbeat.diagnostics

sealed class DiagnosticsEvent {

    enum class Source {
        ENROLLMENT,
        VERIFICATION
    }

    data class AudioEffectsWarning(
        val source: Source,
        val activeEffects: List<String>
    ) : DiagnosticsEvent()

    data class SegmentMetrics(
        val source: Source,
        val segmentId: Long,
        val durationMs: Long,
        val rmsDbfs: Float,
        val clippingRatio: Float,
        val speechRatio: Float,
        val similarity: Float?,
        val matched: Boolean?,
        val warnings: List<String>
    ) : DiagnosticsEvent()
}
