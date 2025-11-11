Samsung Galaxy Tab S6 Lite
Platform	Android Kotlin + Jetpack Foreground Service	Always-on service with lifecycle control
Audio I/O	AudioRecord API (16 kHz mono PCM)	Stream mic samples to the VAD engine using a single reusable frame buffer
VAD Engine (on-device)	① WebRTC VAD (C library + JNI) or ② Silero VAD (Pytorch Lite model)	Detect voice activity frame-by-frame (Stage 1 gate)
Pre/Post Processing	TensorFlow Lite Micro or NumPy Android (for normalization, windowing)	Handle input features (frames → features)
Concurrency	Kotlin Coroutines / Flow	Non-blocking audio streaming
Inter-stage Transport	BoundService callbacks or BroadcastReceiver + SharedFlow	Push detected segments to Stage 2
Testing / Profiling	Android Studio Profiler + Battery Historian + `adb shell dumpsys media.audio_flinger`

| Category                 | Library                              | Notes                         |
| ------------------------ | ------------------------------------ | ----------------------------- |
| **Audio**                | `androidx.media3` or `AudioRecord`   | For mic streaming             |
| **Signal Processing**    | `KissFFT`, `librosa` (dev testing)   | Feature windows / energy calc |
| **ML Inference**         | `TensorFlow Lite` / `PyTorch Mobile` | Run neural VAD                |
| **JNI / NDK**            | `CMakeLists.txt` + `libwebrtcvad.a`  | Native integration            |
| **Logging**              | `Timber` or `Logcat`                 | Diagnostics                   |
| **Battery Optimization** | `WorkManager` + `Foreground Service` | Stay alive on Android 12+     |
| **Testing**              | `JUnit`, `Robolectric`, `Espresso`   | Unit + Instrumentation tests  |


⚡ 5. Implementation Steps

Set up Foreground Service

Acquire mic permission (RECORD_AUDIO), run as START_STICKY.

Audio Buffer Capture

Use AudioRecord (16 kHz mono 16-bit) with ~30 ms frames.

Run VAD

For each frame, call isSpeech(frame) → emit state changes.

Segment Buffers

Collect speech frames until silence > 500 ms.

Emit Events to Stage 2

Forward buffer via ServiceConnection, LocalBroadcast, or SharedFlow.

Performance Testing

Verify < 100 ms latency and < 10 MB RAM usage.

🔋 6. Power & Performance Tips

Use 16-bit PCM (not float) to halve I/O load.

Avoid explicit wake locks—foreground service notification is the sole keep-alive signal.

Pin the audio coroutine to a single IO thread (no context switching) and keep frame buffers reusable to minimize GC.

Batch transmissions to Stage 2 (e.g., every 3 s speech segment).

Disable downstream stages temporarily when battery < 10 %

Profiling workflow: Android Studio Profiler for CPU/memory (<2% CPU target) and Battery Historian for 8-hour drain validation.

Be Minimal in the Service: The service code itself should be minimal. It should only be responsible for:

Managing the AudioRecord stream.

Running the 3-stage pipeline.

Managing the WebSocket connection.

Do not perform any other work, like disk I/O (beyond logging for the PoC) or heavy computation, in this service.

Do Themes and Stuff Matter?
No, themes do not matter at all.

Themes, XML layouts, and other visual "stuff" are part of your app's UI (User Interface), which runs in an Activity. Your Foreground Service is a background component.

When the user closes your app, the Activity is destroyed, and all those UI elements (and their themes) are unloaded from memory. Your service continues to run completely independently in the background. Its performance is determined by the code it executes (the VAD/SV models), not by what the app looks like.