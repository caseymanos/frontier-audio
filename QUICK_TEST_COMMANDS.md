# Quick Test Commands Reference

## 🚀 Quick Start

### 1. Rebuild and Install
```bash
cd FrontierAudio
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Launch App
```bash
adb shell am start -n com.frontieraudio.heartbeat/.MainActivity
```

### 3. Clear and Monitor Logs
```bash
adb logcat -c
adb logcat -s Heartbeat:* CartesiaWebSocket:* Eagle:* MainActivity:*
```

---

## 📊 Log Monitoring Commands

### Watch Cartesia WebSocket Activity
```bash
adb logcat -s CartesiaWebSocket:*
```

### Watch All Stages (VAD, Verification, Transcription)
```bash
adb logcat | grep -E "Stage|VAD|Verification|Transcription|CartesiaWebSocket"
```

### Watch for Errors Only
```bash
adb logcat | grep -E "❌|ERROR|FATAL|error"
```

### Get Last 50 Relevant Logs
```bash
adb logcat -d | grep -E "CartesiaWebSocket|Transcription|Verification" | tail -50
```

### Save Full Logs to File
```bash
adb logcat -d > logs_$(date +%Y%m%d_%H%M%S).txt
```

---

## ✅ Verification Commands

### Check WebSocket Connection Status
```bash
adb logcat -d | grep "WebSocket connection opened"
```

**Expected:**
```
CartesiaWebSocket: ✅ WebSocket connection opened successfully - ready to stream audio
```

### Check for "Invalid Sample Rate" Error (Should be GONE)
```bash
adb logcat -d | grep "invalid sample rate"
```

**Expected:** No results (error is fixed!)

### Check if Voice Profile Loaded
```bash
adb logcat -d | grep "Profile updated: loaded"
```

**Expected:**
```
Heartbeat: Profile updated: loaded
```

### Check Cartesia URL and Header
```bash
adb logcat -d | grep -E "Cartesia-Version header|Cartesia URL"
```

**Expected:**
```
CartesiaWebSocket: Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header
CartesiaWebSocket: URL: wss://api.cartesia.ai/stt/websocket?api_key=***&model=ink-whisper&language=en&encoding=pcm_s16le&sample_rate=16000
```

---

## 🔍 Diagnostic Commands

### Check Current App State
```bash
adb shell dumpsys activity com.frontieraudio.heartbeat | grep "mResumedActivity"
```

### Check Audio Permissions
```bash
adb shell dumpsys package com.frontieraudio.heartbeat | grep -A 5 "granted=true"
```

### Check Network Connectivity
```bash
adb shell ping -c 3 api.cartesia.ai
```

### Get Device Info
```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

---

## 🎯 Testing Workflow Commands

### Full Test Cycle (Copy-Paste Friendly)
```bash
# 1. Clear old logs
adb logcat -c

# 2. Launch app
adb shell am start -n com.frontieraudio.heartbeat/.MainActivity

# 3. Wait for startup
sleep 3

# 4. Check WebSocket status
adb logcat -d | grep "WebSocket connection opened"

# 5. Monitor live activity (Ctrl+C to stop)
adb logcat -s CartesiaWebSocket:* Heartbeat:*
```

### Quick Health Check
```bash
adb logcat -d | grep -E "✅|❌" | tail -20
```

### Check Last Transcription Result
```bash
adb logcat -d | grep "Transcription \[FINAL\]" | tail -5
```

---

## 🐛 Troubleshooting Commands

### Force Stop App
```bash
adb shell am force-stop com.frontieraudio.heartbeat
```

### Clear App Data (Nuclear Option)
```bash
adb shell pm clear com.frontieraudio.heartbeat
```

### Restart App After Clear
```bash
adb shell am start -n com.frontieraudio.heartbeat/.MainActivity
```

### Check if App is Running
```bash
adb shell ps | grep frontieraudio
```

### Kill and Restart ADB
```bash
adb kill-server
adb start-server
adb devices
```

---

## 📸 Screenshot Commands

### Capture Screenshot
```bash
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png ./screenshot_$(date +%Y%m%d_%H%M%S).png
```

### Record Screen Video (30 seconds)
```bash
adb shell screenrecord --time-limit 30 /sdcard/test.mp4 &
# Do your testing...
adb pull /sdcard/test.mp4 ./test_video.mp4
```

---

## 🎬 Live Testing Session Template

```bash
#!/bin/bash
# Save as: test_session.sh

echo "=== Starting FrontierAudio Test Session ==="

# Clear logs
echo "Clearing logs..."
adb logcat -c

# Launch app
echo "Launching app..."
adb shell am start -n com.frontieraudio.heartbeat/.MainActivity
sleep 3

# Check WebSocket
echo "Checking WebSocket connection..."
adb logcat -d | grep "WebSocket connection opened" | tail -1

# Monitor activity
echo "Monitoring logs (press Ctrl+C to stop)..."
echo "Now speak into your device..."
adb logcat -s CartesiaWebSocket:* Heartbeat:* | grep -E "✅|❌|Transcription|Verification"
```

Make executable: `chmod +x test_session.sh`
Run: `./test_session.sh`

---

## 📋 Expected Good Logs

### On App Launch:
```
Heartbeat: Starting Stage 3 transcription pipeline
Heartbeat: Transcription config updated with API key
Heartbeat: Cartesia URL: wss://api.cartesia.ai/stt/websocket
CartesiaWebSocket: Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header
CartesiaWebSocket: ✅ WebSocket connection opened successfully - ready to stream audio
Heartbeat: Profile updated: loaded
```

### During Speech Recognition:
```
Heartbeat: Speech detected (VAD)
Heartbeat: ✅ Speaker verified (similarity: 0.75)
CartesiaWebSocket: ✅ Sent audio chunk: 1024 bytes (512 samples)
CartesiaWebSocket: 📥 Received WebSocket message: {"type":"transcript"...}
CartesiaWebSocket: 📝 Transcription [PARTIAL]: 'Hello'
CartesiaWebSocket: 📝 Transcription [FINAL]: 'Hello, this is a test'
```

---

## 🚨 Red Flags to Watch For

### These should NOT appear (anymore):
```
❌ Cartesia error (400): invalid sample rate  [FIXED!]
❌ WebSocket failure: ...
⚠️ Not connected, cannot stream audio chunk
❌ WebSocket failed to connect
```

### These are OK (expected in normal operation):
```
⚠️ Verification failed  [Normal if not speaking or if ambient noise]
⚠️ Not connected, cannot stream audio chunk  [During reconnection only]
```

---

## 💡 Pro Tips

### Monitor Multiple Things at Once (tmux/screen)
```bash
# Terminal 1: WebSocket logs
adb logcat -s CartesiaWebSocket:*

# Terminal 2: Verification logs
adb logcat | grep "Verification"

# Terminal 3: Error logs
adb logcat *:E
```

### Grep with Color
```bash
adb logcat | grep --color=always -E "✅|❌|Transcription"
```

### Follow Logs in Real-Time
```bash
adb logcat -c && adb logcat -s CartesiaWebSocket:* | tee cartesia_live.log
```

### Find Specific Error
```bash
adb logcat -d | grep -i "error" | grep -v "ERROR_NOT_FOUND" | tail -20
```

---

## 📞 Quick Diagnosis

### "No transcription appearing?"
```bash
# Check these in order:
adb logcat -d | grep "WebSocket connection opened"  # Should see ✅
adb logcat -d | grep "Speaker verified"             # Should see similarity score
adb logcat -d | grep "Sent audio chunk"             # Should see bytes sent
adb logcat -d | grep "Received WebSocket message"   # Should see messages
adb logcat -d | grep "Transcription \[FINAL\]"      # Should see text
```

### "Verification card not turning green?"
```bash
# Check voice profile and similarity:
adb logcat -d | grep "Profile updated"              # Should be "loaded"
adb logcat -d | grep "similarity"                   # Check score vs threshold
```

### "WebSocket keeps disconnecting?"
```bash
# Check connection and errors:
adb logcat -d | grep -E "WebSocket.*clos|WebSocket.*fail"
adb shell ping api.cartesia.ai                      # Check network
```

---

## ✅ Success Confirmation One-Liner

```bash
adb logcat -d | grep -E "WebSocket connection opened successfully|Transcription \[FINAL\]" | tail -10
```

If you see both messages, everything is working! 🎉

---

**Last Updated:** 2025-01-26
**Status:** Cartesia fix verified ✅