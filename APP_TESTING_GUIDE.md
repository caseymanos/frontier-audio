# FrontierAudio App Testing Guide

## ✅ Current Status (2025-01-26)

**Fix Applied:** Cartesia WebSocket header issue resolved
**Build Status:** ✅ Compiled and installed successfully
**WebSocket Status:** ✅ Connected successfully with `Cartesia-Version` header

### Confirmed Working:
- ✅ App launches without crashes
- ✅ Voice profile enrolled (291,840 samples loaded)
- ✅ Cartesia WebSocket connects successfully
- ✅ No more "invalid sample_rate" errors
- ✅ Stages 1, 2, and 3 pipelines initialized

---

## 🎯 Testing Objectives

1. Verify Stage 1 (VAD) detects speech
2. Verify Stage 2 (Speaker Verification) shows green card
3. Verify Stage 3 (Transcription) receives and displays text from Cartesia
4. Check logs for any remaining errors
5. Confirm end-to-end flow works

---

## 📱 App UI Overview

### Main Screen Components

**Top Section:**
- **Status Text**: Shows current app state and permissions
- **Verification Card** (Stage 2): Gray when idle, GREEN when speaker verified
  - Shows similarity score
  - Shows countdown timer
  - Shows diagnostics (signal strength, clipping, speech %)

**Middle Section:**
- **Transcription Card** (Stage 3): Shows transcription results
  - Transcription status
  - Transcription text
  - Location data (lat/long)

**Live Transcript Section:**
- Scrollable view showing recent transcriptions
- Up to 50 lines of transcript history

**Control Buttons:**
- **Open Enrollment**: Create/update voice profile
- **Clear Enrollment**: Delete voice profile
- **Debug Transcription**: Manual transcription testing
- **View Logs**: In-app log viewer

**Configuration Sliders:**
- **Threshold**: Speaker verification threshold (0.00-1.00)
- **Retention**: How long verification stays active (1-30 seconds)
- **Cooldown**: Delay between verifications (0-10 seconds)
- **Calibration**: Auto-adjust threshold based on your voice

---

## 🧪 Test Procedures

### Test 1: Check Initial State

**Steps:**
1. Open the app
2. Tap "View Logs" button
3. Look for these messages:
   ```
   ✅ WebSocket connection opened successfully - ready to stream audio
   Profile updated: loaded
   Cartesia URL: wss://api.cartesia.ai/stt/websocket
   API Key configured: sk_car_i...
   ```

**Expected Result:**
- ✅ WebSocket connected
- ✅ Voice profile loaded
- ✅ No error messages

**If Failed:**
- Check if API key is in `gradle.properties`
- Rebuild: `./gradlew clean :app:assembleDebug`
- Reinstall: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

### Test 2: Verify Voice Profile Enrollment

**Check if enrolled:**
1. Main screen should show "loaded (291840 samples)" in logs
2. "Clear Enrollment" button should be enabled

**If NOT enrolled:**
1. Tap "Open Enrollment" button
2. Follow on-screen instructions to record 3 voice samples
3. Each sample: speak for ~3-5 seconds
4. After all samples, profile will be saved
5. Return to main screen

**Suggested enrollment phrases:**
- "This is my voice for the enrollment process"
- "I am recording my voice profile for speaker verification"
- "This application will recognize my voice using this sample"

---

### Test 3: End-to-End Speech Recognition Flow

**Preparation:**
1. Ensure voice profile is enrolled
2. Make sure you're in a quiet environment
3. Clear old logs (tap "View Logs" → "Clear Logs" button)

**Test Flow:**

**Step 1: Speak a test phrase**
- Speak clearly into the microphone: **"Hello, this is a test of the transcription system"**
- Duration: 3-5 seconds minimum

**Step 2: Watch for Stage 1 (VAD)**
- Look at logs for: "Speech detected" or "VAD"
- The app should detect speech activity
- Expected time: <200ms after you start speaking

**Step 3: Watch for Stage 2 (Verification)**
- **Verification card should turn GREEN**
- Look for: "✅ Speaker verified (similarity: 0.XX)"
- Similarity should be > 0.50 (typical: 0.65-0.85)
- Countdown timer starts (e.g., "Expires in: 5.0 s")

**Step 4: Watch for Stage 3 (Transcription)**
- Look in logs for:
  ```
  ✅ Sent audio chunk: XXXX bytes
  📥 Received WebSocket message: {"type":"transcript"...}
  📝 Transcription [PARTIAL]: '...'
  📝 Transcription [FINAL]: 'Hello, this is a test of the transcription system'
  ```
- **Transcription text should appear in the UI**
- Location coordinates should show (if location permission granted)

**Expected Timeline:**
- 0.0s: Start speaking
- 0.2s: VAD detects speech
- 2.5s: Stop speaking
- 2.7s: VAD segment complete
- 3.0s: Verification complete, green card appears
- 3.2s: Audio chunks sent to Cartesia
- 3.5s: First partial transcription received
- 4.0s: Final transcription received and displayed

**Success Criteria:**
- ✅ Green verification card appears
- ✅ No errors in logs
- ✅ Transcription text matches what you said
- ✅ Location data appears (if enabled)

---

### Test 4: Debug Transcription Feature

**Purpose:** Manually trigger transcription without VAD/verification

**Steps:**
1. Tap "Debug Transcription" button (orange)
2. New screen opens with "Start Transcription" button
3. Tap "Start Transcription"
4. Speak clearly: "Testing the debug transcription feature"
5. Tap "Stop Transcription"
6. Check for results on screen

**Expected:**
- Direct transcription without verification
- Bypasses Stage 1 and Stage 2
- Goes straight to Cartesia STT
- Good for isolating Stage 3 issues

---

### Test 5: View Logs in Detail

**How to access:**
1. Tap "View Logs" button on main screen
2. Logs screen shows last 300 log entries
3. Use "Refresh" button to update
4. Use "Clear" button to reset logs

**What to look for:**

**Good logs (working):**
```
CartesiaWebSocket: Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header
CartesiaWebSocket: ✅ WebSocket connection opened successfully
CartesiaWebSocket: ✅ Sent audio chunk: 1024 bytes (512 samples)
CartesiaWebSocket: 📥 Received WebSocket message: {"type":"transcript"...}
CartesiaWebSocket: 📝 Transcription [FINAL]: 'your text here'
```

**Bad logs (problems):**
```
❌ Cartesia error (400): invalid sample rate  [FIXED by this patch]
❌ WebSocket failure: ...
⚠️ Not connected, cannot stream audio chunk
❌ WebSocket failed to connect after 5000ms timeout
```

**Log Stats:**
- Top of screen shows: "Stats: Total entries: XXX / 500 max"
- Last updated timestamp

---

## 🔍 Troubleshooting

### Issue: No transcription appears

**Checklist:**
1. Is the verification card turning green? 
   - If NO: Voice profile might not match. Try re-enrolling.
   - If YES: Continue to next check.

2. Are audio chunks being sent?
   - Check logs for: "✅ Sent audio chunk: XXXX bytes"
   - If NO: Stage 3 might not be starting. Check logs for errors.
   - If YES: Continue to next check.

3. Are messages being received from Cartesia?
   - Check logs for: "📥 Received WebSocket message"
   - If NO: WebSocket might be disconnected. Check connection status.
   - If YES: Continue to next check.

4. Is transcription being parsed?
   - Check logs for: "📝 Transcription [FINAL]:"
   - If NO: JSON parsing error. Check raw message in logs.
   - If YES: UI update issue. Check MainActivity logs.

### Issue: Verification card never turns green

**Possible causes:**
1. **No speech detected (Stage 1 fail)**
   - Check microphone permissions
   - Ensure you speak for 2+ seconds
   - Check VAD logs for "Speech detected"

2. **Speaker mismatch (Stage 2 fail)**
   - Check similarity score in logs
   - If similarity < 0.40: Re-enroll voice profile
   - If similarity between 0.40-0.50: Adjust threshold slider
   - If no similarity shown: Eagle verification error

3. **Threshold too high**
   - Default: 0.50
   - Try lowering to 0.40 for testing
   - Use calibration feature for optimal setting

### Issue: WebSocket disconnects

**Check logs for:**
```
WebSocket closing: XXXX - reason
WebSocket closed: XXXX - reason
Attempting to reconnect WebSocket...
```

**Common causes:**
- Network timeout (no internet)
- Invalid API key (check gradle.properties)
- Cartesia service issue (check status.cartesia.ai)
- Connection idle >20 seconds (Cartesia auto-closes)

**Solution:**
- Reconnection is automatic (every 5 seconds)
- Check network connectivity
- Verify API key: `sk_car_...` should be present

### Issue: App crashes

**First steps:**
1. Get crash logs: `adb logcat -d > crash_logs.txt`
2. Look for "FATAL EXCEPTION" or "AndroidRuntime"
3. Share relevant stack trace

**Common fixes:**
- Clear app data: Settings → Apps → FrontierAudio → Storage → Clear Data
- Rebuild: `./gradlew clean :app:assembleDebug`
- Check permissions: Audio, Location, Notifications

---

## 🧰 Advanced Testing

### Test with Different Phrases

**Short phrases (1-2 seconds):**
- "Hello"
- "Testing one two three"
- "What's the weather like"

**Medium phrases (3-5 seconds):**
- "This is a test of the speech recognition system"
- "The quick brown fox jumps over the lazy dog"
- "Please transcribe everything I say accurately"

**Long phrases (5-10 seconds):**
- "I'm testing the end-to-end voice recognition pipeline which includes voice activity detection, speaker verification using Eagle, and speech-to-text transcription via Cartesia"
- Dictate a full paragraph

**Challenging phrases:**
- Technical terms: "WebSocket", "Cartesia", "Picovoice"
- Numbers: "One two three four five"
- Mixed: "The API key is SK underscore CAR underscore ABC123"

### Test Edge Cases

**1. Background noise:**
- Test in noisy environment
- Check VAD sensitivity
- Check transcription accuracy

**2. Different voices:**
- Have someone else speak (should NOT verify)
- Check similarity score (should be < threshold)
- Verification card should stay gray

**3. Network interruption:**
- Turn off WiFi while transcribing
- Check reconnection behavior
- Enable WiFi and retry

**4. Rapid succession:**
- Speak, wait for green card, immediately speak again
- Check retention/cooldown timers
- Verify multiple transcriptions appear

**5. Very quiet speech:**
- Whisper into microphone
- Check if VAD detects
- Check signal strength in diagnostics

**6. Very loud speech:**
- Speak loudly/shout
- Check for clipping in diagnostics
- Verify transcription still works

---

## 📊 Performance Benchmarks

### Expected Latencies:
- **VAD Detection**: 50-200ms after speech starts
- **VAD Segment**: 2-3 seconds typical speech duration
- **Speaker Verification**: 100-500ms
- **WebSocket Send**: 10-50ms per chunk
- **Cartesia Response**: 200-800ms for first partial
- **Total (speech → UI)**: 2-4 seconds end-to-end

### Expected Resource Usage:
- **Memory**: ~150MB total (Eagle + app + buffers)
- **CPU**: 5-15% during active transcription
- **Network**: ~16 KB/s during audio streaming (16kHz × 2 bytes)
- **Battery**: Moderate impact (foreground service running)

### Audio Quality Metrics (from diagnostics):
- **Signal Level**: -35 to -10 dBFS (good range)
- **Clipping**: <1% (avoid >5%)
- **Speech Probability**: >80% during speech

---

## 📋 Log Analysis Command Reference

### Get all Cartesia logs:
```bash
adb logcat -d | grep "CartesiaWebSocket" > cartesia_logs.txt
```

### Get recent transcription logs:
```bash
adb logcat -d | grep -E "Transcription|CartesiaWebSocket|Verification" | tail -100
```

### Monitor live logs:
```bash
adb logcat -s Heartbeat:* CartesiaWebSocket:* Eagle:* MainActivity:*
```

### Clear logs and start fresh:
```bash
adb logcat -c
```

### Get only errors:
```bash
adb logcat -d *:E
```

---

## ✅ Success Confirmation Checklist

After testing, confirm all these work:

- [ ] App launches without crashes
- [ ] Voice profile loads on startup
- [ ] WebSocket connects successfully (check logs)
- [ ] No "invalid sample_rate" errors in logs
- [ ] VAD detects speech when speaking
- [ ] Verification card turns green for enrolled speaker
- [ ] Verification card stays gray for unknown speaker
- [ ] Audio chunks are sent to Cartesia
- [ ] Transcription messages received from Cartesia
- [ ] Partial transcriptions appear in logs
- [ ] Final transcription appears in logs
- [ ] Transcription text displays in UI
- [ ] Location data attaches to results (if enabled)
- [ ] Multiple transcriptions work consecutively
- [ ] Debug transcription feature works
- [ ] Log viewer shows detailed logs
- [ ] Calibration feature suggests threshold

---

## 🐛 Known Issues (Post-Fix)

### None Currently!

The "invalid sample_rate" error has been **FIXED** by moving `cartesia_version` from query parameter to `Cartesia-Version` header.

---

## 📞 Reporting Issues

If you encounter problems, collect:

1. **Device Info:**
   - Make/Model: (e.g., Samsung Galaxy S21)
   - Android Version: (e.g., Android 13)
   - App Version: (shown in About screen)

2. **Test Details:**
   - What phrase did you say?
   - How long did you speak?
   - What was the environment? (quiet/noisy)

3. **Logs:**
   ```bash
   adb logcat -d > full_logs.txt
   ```
   Attach the `full_logs.txt` file

4. **Screenshots:**
   - Main screen (showing status)
   - Verification card (during test)
   - Log viewer screen
   - Any error messages

5. **Expected vs Actual:**
   - Expected: "Transcription should appear after 3 seconds"
   - Actual: "No transcription after 10 seconds, green card appeared"

---

## 🎉 Next Steps After Successful Testing

Once all tests pass:

1. **Mark the Cartesia fix as verified** ✅
2. **Document any new issues** found during testing
3. **Consider enhancements:**
   - Real-time partial transcription in UI
   - Word-level timestamp display
   - Confidence score indicators
   - Multi-language support toggle
   - Export transcriptions to file
4. **Production readiness:**
   - Stress testing (long sessions)
   - Battery impact testing
   - Network reliability testing
   - Multi-device testing

---

## 📚 Related Documentation

- **Fix Details**: See `CARTESIA_WEBSOCKET_FIX.md`
- **Testing Guide**: See `TESTING_CARTESIA_FIX.md`
- **Code Changes**: See `CARTESIA_CODE_DIFF.md`
- **Quick Summary**: See `CARTESIA_FIX_SUMMARY.md`
- **Phase 3 Spec**: See `PHASE_3_IMPLEMENTATION.md`
- **Cartesia API**: https://docs.cartesia.ai/api-reference/stt/stt

---

**Last Updated:** 2025-01-26
**Status:** Ready for testing 🚀