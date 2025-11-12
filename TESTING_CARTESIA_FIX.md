# Testing Guide: Cartesia WebSocket Fix

## Overview
This guide helps you verify that the Cartesia WebSocket STT integration is now working correctly after fixing the `cartesia_version` header issue.

## Pre-Testing Setup

### 1. Verify API Key
Ensure your Cartesia API key is configured in the app:
- Open the app
- Navigate to Settings/Configuration
- Verify API key is present (should show first 8 characters + "...")

### 2. Enable Debug Logging
- Make sure `AppLogger` is set to DEBUG or VERBOSE level
- Enable "View Logs" in the app if available

### 3. Clear Previous State
- Force stop the app
- Clear app cache (optional)
- Restart the app fresh

---

## Test Procedure

### Stage 1: Initial Connection Test

**Expected Behavior:**
```
✅ Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header
✅ WebSocket connection opened successfully - ready to stream audio
```

**What to Look For:**
- No "invalid sample_rate" error
- No immediate disconnection/reconnection loop
- Connection status remains stable

**How to Verify:**
1. Launch the app
2. Navigate to "View Logs" screen
3. Look for the connection log messages
4. Verify no error codes (especially 400)

---

### Stage 2: Speech Detection & Verification Test

**Expected Behavior:**
```
Stage 1 (VAD): ✅ Speech detected
Stage 2 (Verification): ✅ Speaker verified (green card appears)
Stage 3 (Transcription): ✅ Preparing to send audio to Cartesia
```

**How to Verify:**
1. Speak into the device: "Hello, this is a test"
2. Wait for VAD to detect speech (2+ seconds)
3. Green verification card should appear
4. Stage 3 should automatically begin

---

### Stage 3: Transcription Streaming Test

**Expected Behavior:**
```
✅ Sent audio chunk: XXXX bytes (YYYY samples)
📥 Received WebSocket message: {"type":"transcript"...}
📝 Transcription [PARTIAL]: 'Hello' (5 chars)
📝 Transcription [PARTIAL]: 'Hello this' (10 chars)
📝 Transcription [FINAL]: 'Hello, this is a test' (21 chars)
```

**What to Look For:**
- Audio chunks being sent successfully
- WebSocket messages being received
- Transcription text appearing (partial and final)
- NO "WebSocket EOF" errors
- NO reconnection attempts during active transcription

**How to Verify:**
1. Continue speaking after green card appears
2. Check logs for "Sent audio chunk" messages
3. Check logs for "Received WebSocket message" 
4. Check logs for "Transcription [PARTIAL]" or "[FINAL]"
5. Verify transcription text appears in the UI

---

### Stage 4: End-to-End Flow Test

**Complete Test Phrase:**
```
"The quick brown fox jumps over the lazy dog"
```

**Expected Complete Log Flow:**
```
[VAD] Speech detected, starting segment
[VAD] Speech segment complete: X.X seconds
[Verification] Verifying speaker...
[Verification] ✅ Speaker verified (similarity: 0.XX)
[Transcription] ✅ WebSocket connected
[Transcription] ✅ Sent audio chunk: XXXX bytes
[Transcription] 📥 Received WebSocket message
[Transcription] 📝 Transcription [PARTIAL]: 'The quick'
[Transcription] 📝 Transcription [PARTIAL]: 'The quick brown fox'
[Transcription] 📝 Transcription [FINAL]: 'The quick brown fox jumps over the lazy dog'
[UI] Displaying transcription with location data
```

---

## Success Criteria Checklist

- [ ] WebSocket connects without "invalid sample_rate" error
- [ ] Connection remains stable (no disconnection loop)
- [ ] Audio chunks are sent successfully
- [ ] Transcription messages are received from Cartesia
- [ ] Partial transcriptions appear in logs
- [ ] Final transcription appears in logs
- [ ] Transcription text appears in the UI
- [ ] Location data is attached to results (if enabled)
- [ ] No crashes or exceptions during transcription
- [ ] Multiple transcription sessions work consecutively

---

## Common Issues & Troubleshooting

### Issue: Still Getting "invalid sample_rate" Error
**Possible Causes:**
- Old APK cached, rebuild required
- Gradle sync needed
- Code change didn't take effect

**Solution:**
```bash
cd FrontierAudio
./gradlew clean
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Issue: WebSocket Connects But No Transcription
**Check:**
- Is audio actually being sent? Look for "Sent audio chunk" logs
- Is the WebSocket still connected when audio is sent?
- Are there any error messages in the Cartesia response?

**Debug:**
```kotlin
// Add this log to verify audio data:
AppLogger.d(TAG, "Audio chunk size: ${audioChunk.size}, first sample: ${audioChunk.firstOrNull()}")
```

### Issue: Connection Timeout
**Check:**
- Network connectivity
- API key validity
- Firewall/proxy settings
- Cartesia service status

**Verify:**
```bash
# Test connectivity
curl -I https://api.cartesia.ai/

# Test with your API key
curl -X GET "https://api.cartesia.ai/" \
  -H "Cartesia-Version: 2025-04-16" \
  -H "X-API-Key: YOUR_API_KEY"
```

### Issue: Partial Transcriptions Only
**This is Normal:**
- Cartesia sends partial results as they become available
- Final results are marked with `"is_final": true`
- UI should show partials but only save finals

### Issue: Audio Quality Poor / Incorrect Transcription
**Check:**
- Sample rate matches (16000 Hz)
- Audio format is correct (PCM S16LE)
- Microphone permissions granted
- Background noise level

---

## Performance Benchmarks

### Expected Latencies:
- **VAD Detection**: 50-200ms
- **Speaker Verification**: 100-500ms
- **WebSocket Connection**: 100-1000ms
- **First Transcription**: 200-800ms after audio sent
- **End-to-End (speech → UI)**: 2-4 seconds

### Expected Resource Usage:
- **Network**: ~16 KB/s during active transcription (16kHz × 2 bytes)
- **Memory**: <50 MB increase during transcription
- **CPU**: <10% on modern devices

---

## Log Analysis Guide

### Good Logs Example:
```
[CartesiaWebSocket] Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header
[CartesiaWebSocket] ✅ WebSocket connection opened successfully - ready to stream audio
[CartesiaWebSocket] ✅ Sent audio chunk: 1024 bytes (512 samples)
[CartesiaWebSocket] 📥 Received WebSocket message: {"type":"transcript","is_final":false...}
[CartesiaWebSocket] 📝 Transcription [PARTIAL]: 'Hello'
[CartesiaWebSocket] 📝 Transcription [FINAL]: 'Hello, this is a test'
```

### Bad Logs Example (Fixed by this patch):
```
[CartesiaWebSocket] Connecting to Cartesia WebSocket...
[CartesiaWebSocket] ✅ WebSocket connection opened
[CartesiaWebSocket] ❌ Cartesia error (400): invalid sample rate
[CartesiaWebSocket] WebSocket closed: 1000 - EOF
[CartesiaWebSocket] Attempting to reconnect WebSocket...
```

---

## Reporting Results

If testing reveals issues, please provide:

1. **Full log excerpt** showing the problem
2. **Exact error message** from Cartesia
3. **Test phrase** you spoke
4. **Device info**: Make/model, Android version
5. **Network conditions**: WiFi/cellular, speed
6. **Timestamp** when the issue occurred

### Log Export Command:
```bash
adb logcat -d | grep -E "CartesiaWebSocket|HeartbeatService" > cartesia_test_logs.txt
```

---

## Next Steps After Successful Testing

Once all tests pass:

1. **Mark the issue as resolved** in project tracking
2. **Update user documentation** if needed
3. **Consider additional features**:
   - Word-level timestamp display
   - Real-time partial transcription UI
   - Transcription confidence indicators
   - Multi-language support UI
4. **Monitor production usage**:
   - Track error rates
   - Measure latencies
   - Collect user feedback

---

## Reference Links

- **Fix Documentation**: See `CARTESIA_WEBSOCKET_FIX.md`
- **Cartesia API Docs**: https://docs.cartesia.ai/api-reference/stt/stt
- **Phase 3 Implementation**: See `PHASE_3_IMPLEMENTATION.md`
- **Debug Thread**: See Zed conversation thread referenced in fix doc