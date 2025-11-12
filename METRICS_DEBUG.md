# Metrics Debugging Guide

## Problem: Metrics Not Showing Up

If you tap "Show Metrics" and see "No metrics collected yet", follow this debugging guide.

## Quick Diagnostics

### Step 1: Check Active Tracking

When you tap "Show Metrics" with no completed metrics, you should see:

```
Debug Info:
  Active tracking: X
  Segment IDs: [123, 456]
  (Waiting for transcription to complete...)
```

**What this means:**
- **Active tracking: 0** → No audio is being processed
- **Active tracking: >0** → Audio is being tracked but hasn't completed yet

### Step 2: Check Logs

Run this command to see metrics flow:

```bash
adb logcat -c  # Clear logs
adb logcat | grep '📊'
```

**Expected log sequence for a successful transcription:**

```
📊 Started metrics tracking: segment=123, metrics_id=1
📊 Segment 123: VAD complete (45ms, audio=1200ms)
📊 Segment 123: Stage2 start
📊 Segment 123: Stage2 complete (123ms, passed=true)
📊 Segment 123: Stage3 start
📊 Segment 123: First partial received (287ms from Stage3 start)
📊 ✅ METRICS COMPLETE: id=1, segment=123, latency=480ms, RTF=0.40x, words=5
```

## Common Issues

### Issue 1: No Tracking Started

**Logs show:** Nothing with 📊

**Cause:** VAD not detecting speech or service not running

**Solution:**
1. Check service is running (notification should be visible)
2. Speak louder or in a quieter environment
3. Check audio permissions

**Verify with:**
```bash
adb logcat | grep "Stage1 segment ready"
```

If you see "Stage1 segment ready" but no "📊 Started metrics tracking", the metrics tracking isn't being called properly.

---

### Issue 2: Tracking Started But Stuck at Stage 2

**Logs show:**
```
📊 Started metrics tracking: segment=123, metrics_id=1
📊 Segment 123: VAD complete (45ms, audio=1200ms)
📊 Segment 123: Stage2 start
⚠️ Stage2 rejected chunk id=123 similarity=0.05
```

**Cause:** Verification failing, metrics cancelled

**Solution:**
1. Re-enroll voice profile
2. Lower verification threshold
3. Enable bypass mode for testing

**Expected:** When verification fails, you should see:
```
📊 Cancelled metrics tracking for segment 123
```

---

### Issue 3: Tracking Stuck at Stage 3

**Logs show:**
```
📊 Started metrics tracking: segment=123, metrics_id=1
📊 Segment 123: VAD complete (45ms, audio=1200ms)
📊 Segment 123: Stage2 start
📊 Segment 123: Stage2 complete (123ms, passed=true)
📊 Segment 123: Stage3 start
Stage 3: Now actively streaming audio to Cartesia (segment 123)
```

**But no completion...**

**Cause:** Transcription not returning results

**Solution:**
1. Check network connection
2. Check Cartesia API status
3. Look for WebSocket errors

**Verify with:**
```bash
adb logcat | grep "Cartesia"
```

Look for:
- "WebSocket connection opened successfully"
- "Transcription [FINAL]:"
- Any error messages

---

### Issue 4: Warning "no active metrics for segment"

**Logs show:**
```
⚠️ Cannot mark Stage2 start: no active metrics for segment 123
```

**Cause:** Segment ID mismatch or tracking wasn't started

**Solution:**
1. Verify that `startTracking()` is called for every segment
2. Check that the same segment ID is used throughout

**Debug steps:**
```bash
# See all segment IDs being created and tracked
adb logcat | grep -E "segment ready|Started metrics tracking"
```

Both should show the same segment IDs.

---

### Issue 5: Empty Transcripts

**Logs show:**
```
📊 Segment 123: Stage3 start
Transcription [FINAL]: '' (0 chars)
Received transcription but no segment ID tracked for metrics
```

**Cause:** Cartesia returned empty transcript

**Solution:**
1. Audio may be too short
2. Audio quality issue
3. Network issue truncated audio

**Not a metrics bug** - this is upstream transcription issue.

---

### Issue 6: Metrics Show Active But Never Complete

**Metrics button shows:**
```
Active tracking: 5
Segment IDs: [120, 121, 122, 123, 124]
```

**Cause:** Transcriptions started but never finished

**Solution:**
1. Check if transcription results are arriving:
```bash
adb logcat | grep "Transcription \[FINAL\]"
```

2. If no FINAL results, check Stage 3:
```bash
adb logcat | grep "Stage 3"
```

3. Reset state by restarting the service or clearing metrics programmatically

---

## Manual Testing

### Test with Bypass Mode

1. Enable "Bypass Speaker Verification" switch
2. Speak a phrase
3. Check metrics

**This isolates Stage 3 transcription** from verification issues.

**Expected logs:**
```
📊 Started metrics tracking: segment=X
📊 Segment X: VAD complete
📊 Segment X: Stage2 start
📊 Segment X: Stage2 complete (Xms, passed=true)  # Will show bypass=true internally
📊 Segment X: Stage3 start
📊 ✅ METRICS COMPLETE: id=Y, segment=X, ...
```

---

## Debugging Code

### Add temporary logging

In `HeartbeatService.kt`, add to `handleTranscriptionResult()`:

```kotlin
private suspend fun handleTranscriptionResult(result: TranscriptionResult) {
    AppLogger.i(TAG, "🔍 Transcription: isFinal=${result.isFinal}, length=${result.transcript.length}, currentStreamingSegmentId=$currentStreamingSegmentId")
    
    // ... rest of method
}
```

This will show you:
- If transcription results are arriving
- What the current segment ID is
- If the segment ID is -1 (not tracked)

---

## Force Metrics Emission for Testing

You can manually trigger a test metric:

```kotlin
// In MainActivity or wherever you have access to the service
val service = HeartbeatService.getInstance()
val collector = service.getMetricsCollector()

// Create a test metric
collector.startTracking(999L)
collector.markVadComplete(999L, 1000L, 16000)
collector.markStage2Start(999L)
collector.markStage2Complete(999L, 0.5f, 0.1f, true, false)
collector.markStage3Start(999L)
collector.markFinalTranscript(999L, "Test transcription")

// Now check metrics display
```

If this works, the metrics system is functioning - the issue is with the pipeline integration.

---

## Verification Checklist

- [ ] Service is running (notification visible)
- [ ] Voice profile is enrolled
- [ ] Speaking and getting transcriptions (bypass mode works)
- [ ] Seeing "📊 Started metrics tracking" in logs
- [ ] Seeing "Stage1 segment ready" in logs
- [ ] Seeing "📊 ✅ METRICS COMPLETE" in logs
- [ ] Metrics button shows completed metrics

---

## Log Filtering Commands

### All metrics flow:
```bash
adb logcat | grep '📊'
```

### Full pipeline:
```bash
adb logcat | grep -E '📊|Stage[123]|segment ready|Transcription \[FINAL\]'
```

### Just completions:
```bash
adb logcat | grep 'METRICS COMPLETE'
```

### Errors and warnings:
```bash
adb logcat | grep -E '⚠️|❌' | grep -i metric
```

---

## Still Not Working?

If you've gone through all the above and still no metrics:

1. **Check the build:** Ensure the latest code with metrics is installed
   ```bash
   ./gradlew clean assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Restart the service:** Stop and restart the app completely

3. **Check logs from the start:**
   ```bash
   adb logcat -c
   # Start app
   # Speak
   adb logcat -d | grep -E '📊|Metrics'
   ```

4. **Verify the code:** Make sure these lines are present:
   - In `segmentAssembler` callback: `metricsCollector.startTracking(segment.id)`
   - In `onVerificationSuccess`: `metricsCollector.markStage3Start(segmentId)`
   - In `handleTranscriptionResult`: `metricsCollector.markFinalTranscript(...)`

5. **File a detailed issue** with:
   - Full logs from a single transcription attempt
   - Current segment ID values
   - Active tracking count
   - Completed metrics count

---

## Success Indicators

When everything works, you'll see:

**In Logs:**
```
📊 Started metrics tracking: segment=123, metrics_id=1
📊 Segment 123: VAD complete (45ms, audio=1200ms)
📊 Segment 123: Stage2 start
📊 Segment 123: Stage2 complete (123ms, passed=true)
📊 Segment 123: Stage3 start
Stage 3: Now actively streaming audio to Cartesia (segment 123)
📊 Segment 123: First partial received (287ms from Stage3 start)
📝 Transcription [FINAL]: 'Yeah, that would be good.' (26 chars)
📊 ✅ METRICS COMPLETE: id=1, segment=123, latency=480ms, RTF=0.40x, words=5
```

**In UI (Metrics Button):**
```
=== Session Stats ===
Count: 1
Audio: 1.2s
Avg Latency: 480ms (RTF: 0.40x)
...
```

**That's the goal!** 🎯