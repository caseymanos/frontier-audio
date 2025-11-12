# Transcription Metrics Implementation

## Overview

This document describes the comprehensive metrics tracking system implemented for the FrontierAudio app. The system tracks timing and performance data through the entire audio processing pipeline: VAD → Speaker Verification → Transcription.

## Features

✅ **Complete Pipeline Tracking**
- Tracks timestamps at every stage of the audio processing pipeline
- Captures VAD (Voice Activity Detection) latency
- Measures Speaker Verification (Stage 2) processing time
- Records Transcription (Stage 3) latency including time to first partial result

✅ **Key Metrics Calculated**
- **TTCT (Time To Complete Transcription)**: Total end-to-end latency from speech detection to final transcript
- **Real-Time Factor (RTF)**: Processing time / audio duration (lower is better, <1.0 means faster than real-time)
- **Speaking Rate**: Words per second in the transcribed audio
- **Stage Breakdown**: Individual latency for each pipeline stage
- **Verification Stats**: Similarity scores, pass/fail rates, bypass mode tracking

✅ **User Interface**
- "Show Metrics" button in MainActivity to toggle metrics display
- Displays both aggregate session statistics and individual transcription details
- Shows last 10 transcriptions with full timing breakdown
- Monospace font for easy reading of aligned data
- Text is selectable for easy copying

✅ **Export Capability**
- CSV export functionality with all timing data
- Suitable for analysis in spreadsheet tools
- Includes all key metrics: latencies, RTF, word counts, verification results, transcripts

## Architecture

### Core Components

#### 1. **TranscriptionMetrics.kt**
Location: `app/src/main/java/com/frontieraudio/heartbeat/metrics/TranscriptionMetrics.kt`

Contains the data classes for metrics:

- **`TranscriptionMetrics`**: Immutable data class holding all timing information for a single transcription
  - VAD timestamps (start/end)
  - Stage 2 timestamps (start/end)
  - Stage 3 timestamps (start/end/first partial)
  - Audio characteristics (duration, sample count)
  - Transcription results (text, word count)
  - Verification data (similarity, threshold, pass/fail)
  - Calculated properties: latencies, RTF, words per second

- **`MetricsBuilder`**: Mutable builder for constructing `TranscriptionMetrics` as data flows through pipeline

- **`AggregateStats`**: Summary statistics across multiple transcriptions
  - Count, total audio time, average/min/max latencies
  - Average RTF and per-stage latencies
  - Total word count and verification pass rate

#### 2. **MetricsCollector.kt**
Location: `app/src/main/java/com/frontieraudio/heartbeat/metrics/MetricsCollector.kt`

Thread-safe metrics collection manager:

- **Tracking lifecycle methods**:
  - `startTracking(segmentId)`: Initialize metrics for new audio segment
  - `markVadComplete(segmentId, audioMs, samples)`: Record VAD completion
  - `markStage2Start(segmentId)`: Record verification start
  - `markStage2Complete(segmentId, similarity, threshold, passed, bypass)`: Record verification completion
  - `markStage3Start(segmentId)`: Record transcription start
  - `markFirstPartial(segmentId)`: Record first partial transcript received
  - `markFinalTranscript(segmentId, text)`: Complete metrics and emit result
  - `cancelTracking(segmentId)`: Cancel tracking for failed verification

- **Data access**:
  - `getCompleted()`: Get all completed metrics
  - `getAggregateStats()`: Get session-wide statistics
  - `exportCsv()`: Export all metrics as CSV
  - `clear()`: Clear all completed metrics

- **Flow emission**: Emits `TranscriptionMetrics` via SharedFlow for real-time updates

#### 3. **Integration Points**

##### HeartbeatService.kt
The service integrates metrics tracking at each pipeline stage:

```kotlin
// VAD (Stage 1) - in segmentAssembler callback
metricsCollector.startTracking(segment.id)
metricsCollector.markVadComplete(segment.id, segment.durationMillis, segment.samples.size)

// Stage 2 - in verifyChunk()
metricsCollector.markStage2Start(chunk.id)

// Stage 2 completion - in onVerificationSuccess()
metricsCollector.markStage2Complete(segmentId, similarity, threshold, true, bypassVerification)

// Stage 3 start - in onVerificationSuccess() before streaming
metricsCollector.markStage3Start(segmentId)

// Stage 3 results - in handleTranscriptionResult()
if (!result.isFinal && result.transcript.isNotBlank()) {
    metricsCollector.markFirstPartial(segmentId)
} else if (result.isFinal && result.transcript.isNotBlank()) {
    metricsCollector.markFinalTranscript(segmentId, result.transcript)
}

// Verification failure - in onVerificationFailure()
metricsCollector.cancelTracking(chunk.id)
```

Public accessor:
```kotlin
fun getMetricsCollector(): MetricsCollector = metricsCollector
```

##### MainActivity.kt
UI components for displaying metrics:

- **Button**: `metricsButton` - Toggles metrics display
- **ScrollView**: `metricsScrollView` - Contains metrics display
- **TextView**: `metricsDisplayText` - Shows formatted metrics

Methods:
- `toggleMetricsDisplay()`: Show/hide metrics
- `showMetrics()`: Fetch and display metrics from service
- `hideMetrics()`: Hide metrics display

##### TranscriptionResult.kt
Updated to include optional metrics:
```kotlin
data class TranscriptionResult(
    val segment: VerifiedSpeechSegment,
    val transcript: String,
    val confidence: Float?,
    val isFinal: Boolean,
    val processingTimeMs: Long,
    val locationData: LocationData?,
    val metrics: TranscriptionMetrics? = null  // NEW
)
```

## Metrics Display Format

### Individual Transcription Example:
```
=== Metrics #42 ===
📝 "Yeah, that would be good." (5 words)
⏱️  Audio: 1.2s
   VAD:    45ms
   Stage2: 123ms
   Stage3: 312ms
   1st partial: 287ms
   TOTAL:  480ms
📊 RTF: 0.40x
   Rate: 4.2 words/sec
🔐 Similarity: 0.175 (PASS)
```

### Aggregate Statistics Example:
```
=== Session Stats ===
Count: 15
Audio: 18.5s
Avg Latency: 523ms (RTF: 0.45x)
Range: 312ms - 892ms

Stage Breakdown (Avg):
  VAD:    48ms
  Stage2: 135ms
  Stage3: 340ms

Words: 73
Pass Rate: 93.3%
```

## Usage

### In the App UI

1. Run the FrontierAudio app and ensure HeartbeatService is running
2. Enroll your voice profile if not already done
3. Speak some phrases (they will be transcribed if verification passes)
4. Tap the **"Show Metrics"** button (purple button below the live transcript)
5. View:
   - Session aggregate statistics at the top
   - Last 10 individual transcriptions below with full timing breakdown
6. Tap **"Hide Metrics"** to collapse the display

### Programmatic Access

```kotlin
// Get the service
val service = HeartbeatService.getInstance()

// Get metrics collector
val collector = service.getMetricsCollector()

// Access completed metrics
val allMetrics = collector.getCompleted()

// Get aggregate stats
val stats = collector.getAggregateStats()

// Export to CSV
val csv = collector.exportCsv()

// Clear metrics
collector.clear()

// Listen for new metrics in real-time
lifecycleScope.launch {
    collector.metricsFlow.collect { metrics ->
        // Handle new completed metrics
        Log.i("Metrics", "New transcription: ${metrics.transcript}, latency=${metrics.totalLatencyMs}ms")
    }
}
```

### CSV Export Format

The CSV includes these columns:
- ID: Unique transcription ID
- Timestamp: When VAD started (SystemClock.elapsedRealtime())
- Total_Ms: Total end-to-end latency
- Audio_Ms: Actual audio duration
- VAD_Ms: VAD processing latency
- Stage2_Ms: Verification latency
- Stage3_Ms: Transcription latency
- Time_To_First_Partial_Ms: Time to first partial result (if any)
- Words: Word count
- RTF: Real-time factor
- Similarity: Verification similarity score
- Threshold: Verification threshold used
- Pass: Verification result (true/false)
- Bypass: Whether verification was bypassed
- Transcript: The transcribed text (quoted)

## Performance Considerations

### Memory Management
- Metrics collector keeps only the last 100 completed metrics in memory
- Older metrics are automatically removed to prevent memory issues
- Each metric is ~200-500 bytes depending on transcript length

### Thread Safety
- Uses `ConcurrentHashMap` for active metrics tracking
- `synchronized` block for completed metrics list
- Safe to call from multiple coroutines

### Timing Accuracy
- All timestamps use `SystemClock.elapsedRealtime()` for consistency
- Millisecond precision
- Not affected by system time changes or timezone

## Key Insights from Metrics

### What to Look For

1. **RTF < 1.0**: System is processing faster than real-time (good!)
2. **High Stage 3 latency**: Network or Cartesia API slow
3. **High Stage 2 latency**: Voice verification model taking too long
4. **Low pass rate**: Voice profile may need re-enrollment
5. **Consistent partial timing**: Good for understanding user experience

### Example Scenarios

**Scenario 1: Fast Processing**
```
Audio: 1.5s, Total: 450ms, RTF: 0.30x
✅ System is very responsive, 3x faster than real-time
```

**Scenario 2: Slow Transcription**
```
Audio: 2.0s, Stage3: 3500ms, RTF: 1.85x
⚠️ Transcription is slower than real-time, check network/API
```

**Scenario 3: Verification Issues**
```
Pass Rate: 45%
⚠️ Many failures, consider re-enrolling voice profile or lowering threshold
```

## Future Enhancements

Potential improvements:

1. **Metrics Export to File**: Save CSV directly to device storage
2. **Historical Charts**: Visualize latency trends over time
3. **Alerting**: Notify when metrics exceed thresholds
4. **Network Metrics**: Add Cartesia WebSocket connection/disconnection tracking
5. **Audio Quality Metrics**: Track clipping, noise levels from diagnostics
6. **Per-Word Timing**: Use Cartesia's word-level timestamps if available
7. **Confidence Tracking**: Track transcription confidence scores over time

## Troubleshooting

### Metrics Not Showing
- Ensure HeartbeatService is running (check notification)
- Speak some phrases to generate metrics
- Check that transcription is working (enable bypass mode to test)

### Missing Timestamps
- Check logs for "Metrics" tag to see tracking lifecycle
- Verify that all pipeline stages are executing
- Look for "cancelled metrics tracking" messages

### Unexpected Latencies
- VAD latency should be <100ms typically
- Stage 2 (verification) typically 50-200ms
- Stage 3 (transcription) typically 200-800ms depending on audio length and network
- If Stage 3 > 2 seconds, check network connection or Cartesia API status

## Related Files

- `app/src/main/java/com/frontieraudio/heartbeat/metrics/TranscriptionMetrics.kt`
- `app/src/main/java/com/frontieraudio/heartbeat/metrics/MetricsCollector.kt`
- `app/src/main/java/com/frontieraudio/heartbeat/HeartbeatService.kt` (integration)
- `app/src/main/java/com/frontieraudio/heartbeat/MainActivity.kt` (UI)
- `app/src/main/res/layout/activity_main.xml` (UI layout)

## Implementation Date

December 2024

## Summary

This metrics system provides comprehensive visibility into the audio processing pipeline, helping developers and users understand:
- How fast the system responds to speech
- Where bottlenecks occur
- Whether verification is working properly
- Overall system performance characteristics

The metrics are accurate, lightweight, and easy to access both in the UI and programmatically.