package com.frontieraudio.heartbeat.audio

import android.util.Log
import com.frontieraudio.heartbeat.SpeechSegment

class SpeechSegmentAssembler(
    private val sampleRateHz: Int,
    private val frameSizeSamples: Int,
    preRollDurationMs: Int,
    private val onSegmentReady: suspend (SpeechSegment) -> Unit,
) {

    private val frameDurationNs: Long =
        (frameSizeSamples.toLong() * 1_000_000_000L) / sampleRateHz
    private val preRollFrameCount: Int =
        ((preRollDurationMs.toLong() * sampleRateHz) / (frameSizeSamples * 1_000L))
            .toInt()
            .coerceAtLeast(1)
    private val streamingChunkFrameCount: Int =
        ((STREAMING_CHUNK_MS.toLong() * sampleRateHz) / (frameSizeSamples * 1_000L))
            .toInt()
            .coerceAtLeast(1)

    private val preRollFrames = ArrayDeque<ShortArray>(preRollFrameCount)
    private var activeFrames: MutableList<ShortArray>? = null
    private var segmentStartNs: Long = 0L
    private var segmentIdCounter: Long = 0L

    suspend fun onFrame(frame: ShortArray, isSpeech: Boolean, frameTimestampNs: Long) {
        if (isSpeech) {
            ensureSegmentStarted(frameTimestampNs)
            activeFrames?.add(frame)

            // If chunk is full, emit it
            if (activeFrames?.size == streamingChunkFrameCount) {
                emitChunk(frameTimestampNs)
            }
        } else {
            // If there was an active segment, finish it by emitting the last partial chunk
            if (activeFrames != null) {
                finishSegment(frameTimestampNs)
            }
            addPreRoll(frame)
        }
    }

    fun reset() {
        preRollFrames.clear()
        activeFrames = null
        segmentStartNs = 0L
    }

    private fun ensureSegmentStarted(frameTimestampNs: Long) {
        if (activeFrames != null) return

        val frames = mutableListOf<ShortArray>()
        if (preRollFrames.isNotEmpty()) {
            frames.addAll(preRollFrames)
            preRollFrames.clear()
        }
        activeFrames = frames
        segmentStartNs = frameTimestampNs - frameDurationNs * frames.size
    }

    private fun addPreRoll(frame: ShortArray) {
        if (preRollFrames.size == preRollFrameCount) {
            preRollFrames.removeFirst()
        }
        preRollFrames.addLast(frame)
    }

    private suspend fun emitChunk(chunkEndTimestampNs: Long) {
        val frames = activeFrames ?: return
        Log.d(TAG, "Emitting streaming chunk with ${frames.size} frames")

        // Create segment from the current frames
        val segment = createSegment(frames, chunkEndTimestampNs)
        onSegmentReady(segment)

        // Reset for the next chunk
        activeFrames = mutableListOf()
        segmentStartNs = chunkEndTimestampNs
    }

    private suspend fun finishSegment(frameTimestampNs: Long) {
        val frames = activeFrames ?: return
        activeFrames = null // End the segment

        if (frames.isEmpty()) {
            Log.d(TAG, "finishSegment called with no frames, ignoring.")
            return
        }

        // Emit the final partial chunk
        Log.d(TAG, "Finishing segment with final partial chunk of ${frames.size} frames")
        val segment = createSegment(frames, frameTimestampNs)
        onSegmentReady(segment)
    }

    private fun createSegment(frames: List<ShortArray>, endTimestampNs: Long): SpeechSegment {
        val totalSamples = frames.sumOf { it.size }
        val merged = ShortArray(totalSamples)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(merged, destinationOffset = offset)
            offset += frame.size
        }

        val segmentId = ++segmentIdCounter
        return SpeechSegment(
            id = segmentId,
            startTimestampNs = segmentStartNs,
            endTimestampNs = endTimestampNs,
            sampleRateHz = sampleRateHz,
            frameSizeSamples = frameSizeSamples,
            samples = merged
        )
    }

    companion object {
        private const val TAG = "SpeechSegmentAssembler"
        private const val STREAMING_CHUNK_MS = 500
    }
}