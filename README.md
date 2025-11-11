# Heartbeat VAD Service

An always-on Android Foreground Service that streams microphone PCM audio through the Silero-based `android-vad` detector to flag human speech. Tuned for Samsung Galaxy Tab S6 Lite (Android 13/14) with `minSdk 26`, `targetSdk 34`, and `android:foregroundServiceType="microphone"`.

## Gradle & Build

1. Ensure Android Studio Iguana+ with the Android Gradle Plugin 8.5.x.
2. Open the repository (`com.frontieraudio.heartbeat`).
3. Sync Gradle, then build & deploy the `app` module to the device.
4. On first launch grant `RECORD_AUDIO` and (Android 13+) `POST_NOTIFICATIONS`.

## Runtime Behavior

- `HeartbeatService` runs as a foreground service (`START_STICKY`) with no explicit wake locks—the FGS notification is the only keep-alive signal.
- Audio is captured via `AudioRecord` (`16 kHz`, mono, `PCM_16BIT`) using `VOICE_RECOGNITION` and a single reusable frame buffer.
- Stage 1 VAD (Silero `Mode.NORMAL`, speech=80 ms, silence=300 ms) gates Stage 2 speaker verification and Stage 3 cloud streaming; only the first speech transition logs `VAD: Speech Detected`.
- `BootCompletedReceiver` relaunches the service after device reboots.

## Profiling & Validation

Stage 1 success criteria: sustained **< 2% CPU** with memory flat during the idle listening state. Always capture benchmarks before moving on to Stage 2 (speaker verification) and Stage 3 (cloud streaming).

### Android Studio Profiler

1. Deploy the app, grant microphone + notification permissions, and confirm the foreground notification is present.
2. Open **Run ▸ Profiler**, select the `Heartbeat` process, then start a **Sampled** CPU recording (lowest overhead).
3. Allow at least 2 minutes of idle capture to observe steady-state CPU and memory.
4. Note:
   - Average CPU % (target < 1–2%).
   - Java/Kotlin heap + native heap (ONNX Runtime) stability.
5. Export the trace for regression tracking.

### Battery Historian

1. Collect a bugreport after an extended idle run (e.g., 8-hour battery test mentioned in the PoC).
2. Load the report into [Battery Historian](https://developer.android.com/studio/profile/battery-historian) and inspect:
   - Foreground service uptime (should match the test window).
   - Partial wake locks (should be **zero** since the FGS replaces wakelocks).
   - CPU frequency residency / top consumers.
3. Use these insights to validate that the Stage 1 gate keeps downstream stages off most of the time.

### TFLite / ONNX Runtime Benchmarks

Silero runs through ONNX Runtime. To isolate the model cost:

```bash
adb shell am force-stop com.frontieraudio.heartbeat
adb shell am start-foreground-service com.frontieraudio.heartbeat/.HeartbeatService
adb shell am profile start com.frontieraudio.heartbeat global
```

Alternatively, use ONNX Runtime’s `ort_benchmark` (if flashed onto the device) to replay the Silero `.onnx` asset under the same CPU affinity and thread count. Target < 2% CPU combined for the service loop plus VAD inference.

Document each profiling run (date, device build, CPU %, memory MB, Historian observations) in `docs/FullAnalysis.md` to track regressions before Stage 2 integration.

