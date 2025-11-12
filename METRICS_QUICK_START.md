# Metrics Quick Start Guide

## What Are Metrics?

The metrics system tracks the timing and performance of your voice transcriptions through three stages:
1. **Stage 1 (VAD)**: Voice Activity Detection - detects when you speak
2. **Stage 2 (Verification)**: Confirms it's your voice using speaker verification
3. **Stage 3 (Transcription)**: Sends audio to Cartesia API and receives transcript

## How to Use

### Step 1: Speak Some Phrases
- Make sure your voice profile is enrolled
- Speak clearly into your device
- Wait for transcriptions to appear

### Step 2: View Metrics
1. Scroll down in the main screen
2. Find the **purple "Show Metrics"** button (below the live transcript)
3. Tap it to see your metrics

### Step 3: Interpret the Results

#### What You'll See

**Session Stats (Top):**
```
=== Session Stats ===
Count: 15                    ← Total transcriptions
Audio: 18.5s                 ← Total audio you spoke
Avg Latency: 523ms           ← Average processing time
RTF: 0.45x                   ← Real-Time Factor (see below)
Range: 312ms - 892ms         ← Fastest to slowest
Words: 73                    ← Total words transcribed
Pass Rate: 93.3%             ← Verification success rate
```

**Individual Transcriptions (Below):**
```
=== Metrics #42 ===
📝 "Yeah, that would be good." (5 words)
⏱️  Audio: 1.2s              ← How long you spoke
   VAD:    45ms              ← Stage 1 time
   Stage2: 123ms             ← Stage 2 time
   Stage3: 312ms             ← Stage 3 time
   TOTAL:  480ms             ← End-to-end time
📊 RTF: 0.40x                ← Real-Time Factor
   Rate: 4.2 words/sec       ← Speaking rate
🔐 Similarity: 0.175 (PASS)  ← Verification score
```

## Key Metrics Explained

### TTCT (Time To Complete Transcription)
- **What**: Total time from when you start speaking to when transcript appears
- **Shown as**: "TOTAL" in the timing breakdown
- **Good**: Under 500ms for short phrases
- **Example**: If you speak for 1.5 seconds and TTCT is 450ms, that's great!

### RTF (Real-Time Factor)
- **What**: How fast the system processes compared to real-time
- **Formula**: Processing Time / Audio Duration
- **Good**: RTF < 1.0 (system is faster than real-time)
- **Examples**:
  - RTF = 0.5x → System is 2x faster than real-time ✅
  - RTF = 1.0x → System processes at exactly real-time speed ⚠️
  - RTF = 2.0x → System is slower than real-time ❌

### Words Per Second
- **What**: Your speaking rate
- **Typical**: 2-4 words/sec for normal speech
- **Fast**: 5+ words/sec

### Verification Similarity
- **What**: How closely your voice matches the enrolled profile
- **Range**: 0.0 to 1.0 (higher is better)
- **Pass**: Above your configured threshold (default 0.01-0.1)
- **Example**: 0.175 is a good match

### Pass Rate
- **What**: Percentage of attempts that passed verification
- **Good**: Above 80%
- **Low**: Below 50% → Consider re-enrolling your voice profile

## Stage Breakdown

### Stage 1 (VAD): 20-100ms typical
- Detects speech in audio stream
- Very fast, hardware-accelerated
- If high: Device performance issue

### Stage 2 (Verification): 50-200ms typical
- Verifies it's your voice using Picovoice Eagle
- Depends on audio length and device speed
- If high: Check device performance or audio quality

### Stage 3 (Transcription): 200-800ms typical
- Sends audio to Cartesia WebSocket
- Receives transcript back
- Depends on: audio length, network speed, API response time
- If high (>1500ms): Check internet connection

## Common Scenarios

### ✅ Great Performance
```
Audio: 1.5s
Total: 450ms
RTF: 0.30x
Pass Rate: 95%
```
**Interpretation**: System is very responsive, 3x faster than real-time, verification working well.

### ⚠️ Slow Network
```
Audio: 2.0s
Stage3: 3500ms
RTF: 1.85x
```
**Interpretation**: Transcription stage is slow. Check your internet connection.

### ⚠️ Verification Problems
```
Pass Rate: 45%
Avg Similarity: 0.05
```
**Interpretation**: Many verification failures. Try:
- Re-enroll your voice profile
- Lower the verification threshold
- Check audio quality (no echo/noise)

### 🔍 Short Phrases Issue
```
Audio: 0.3s (1 word)
Similarity: 0.02 (FAIL)
```
**Interpretation**: Very short phrases are hard to verify. This is normal. Longer phrases (2+ seconds) work better.

## Tips for Best Results

1. **Speak Clearly**: 2-3 seconds per phrase
2. **Quiet Environment**: Reduce background noise
3. **Re-Enroll**: If pass rate drops, re-enroll your profile
4. **Check Network**: Stage 3 depends on internet speed
5. **Review Metrics**: Look for patterns in failures

## Exporting Data

Want to analyze metrics in a spreadsheet?

```kotlin
// In code:
val service = HeartbeatService.getInstance()
val csv = service.getMetricsCollector().exportCsv()
// Save csv to file or share
```

The CSV includes all timing data, transcripts, and verification scores.

## Troubleshooting

### "Service not available"
- HeartbeatService isn't running
- Start the app and give audio permissions

### No metrics showing
- Speak some phrases first to generate data
- Make sure transcription is working (try bypass mode)

### All timestamps are zero
- Check logs for "Metrics" tag
- Verify pipeline stages are executing

### Metrics look wrong
- Clear metrics and start fresh: `collector.clear()`
- Check that device clock is working

## Quick Reference Card

| Metric | Good | Warning | Bad |
|--------|------|---------|-----|
| RTF | < 0.6x | 0.6-1.0x | > 1.0x |
| Pass Rate | > 80% | 50-80% | < 50% |
| Stage 1 (VAD) | < 100ms | 100-200ms | > 200ms |
| Stage 2 (Verify) | < 150ms | 150-300ms | > 300ms |
| Stage 3 (Transcribe) | < 600ms | 600-1200ms | > 1200ms |
| Total Latency | < 500ms | 500-1000ms | > 1000ms |

## Need More Details?

See `METRICS_IMPLEMENTATION.md` for:
- Full technical architecture
- Code examples
- Integration details
- Advanced usage

## Have Questions?

Check the logs:
```
adb logcat | grep -E "Metrics|Stage[123]"
```

Look for:
- "Started metrics tracking"
- "Metrics complete"
- Stage timing logs