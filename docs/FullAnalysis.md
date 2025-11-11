
An Architectural Guide: Building a Selective, Always-On Audio Application on Android


I. Executive Summary and The Core Architectural Pivot


A. Direct Answer: From Vision to Viable Plan

The product requirement, "# Always-On Selective Speaker Android App With Cloud Transcription," represents a clear and compelling vision. However, as a starting point for development, it is insufficient. The one-line description masks several high-risk, project-defining assumptions that must be addressed before an effective architecture can be finalized.
The primary risks are not in the cloud, but on the device itself:
The Operating System vs. "Always-On": The greatest challenge is surviving the aggressive, non-standard background process termination policies of specific Android Original Equipment Manufacturers (OEMs). The selected test platform, a Samsung device 1, is notoriously problematic and implements proprietary process-killing mechanisms that go far beyond standard Android behavior.1
The Battery vs. "Always-On": A service that is "always-on" and always processing audio through complex AI models will catastrophically drain the device battery, leading to user uninstalls. The architectural challenge is to build a service that is "always-on listening" but remains in a power-efficiently gated state for the vast majority of its lifecycle.
Cloud Latency vs. Reality: The "100ms" latency target is aggressive and easily misunderstood. The noted "100ms" for Cartesia, for example, refers to its Text-to-Speech (TTS) "Time to First Audio" (TTFA) 2, not its Speech-to-Text (STT) transcription latency. A viable plan must differentiate these metrics and benchmark the correct one: streaming STT latency, often measured as "Time-to-Complete-Transcript" (TTCT).4
A successful implementation hinges on a core architectural pivot. The goal is not to build a single, monolithic "always-on" service. Instead, the correct approach is to design a multi-stage, on-device filtering pipeline. This architecture is the only way to satisfy the "Always-On" requirement while preserving battery life, minimizing cloud costs, and meeting the "Selective Speaker" mandate.

B. The Recommended 3-Stage Pipeline (The Blueprint)

This 3-stage architecture serves as the foundational blueprint for the project. It moves from low-power, computationally cheap checks to high-power, computationally expensive operations, with each stage acting as a gate for the next.
Stage 1 (On-Device): Always-On, Low-Power Gate (VAD)
Component: Voice Activity Detection (VAD) model.
State: Runs 24/7 inside the Android Foreground Service.
Job: This component continuously listens to the raw microphone audio stream. Its only job is to differentiate human speech from silence or background noise. This is computationally very cheap. For example, a lightweight VAD model can be under 2MB and process a 30ms audio chunk in approximately 1ms.5
Trigger: If the VAD model classifies an audio chunk as is_speech == true, it triggers Stage 2. Otherwise, it does nothing and the pipeline remains in its low-power state.
Stage 2 (On-Device): The "Selective Speaker" Filter (SV)
Component: Speaker Verification (SV) Model.
State: Dormant. This component is only loaded into memory and executed when triggered by Stage 1.
Job: This component receives the audio frames that VAD has confirmed as "speech." It analyzes these frames to create a "voiceprint" and compares it to the pre-enrolled voiceprint of the target user.7
Trigger: If the SV model determines is_speech_by_enrolled_user == true (a match), it triggers Stage 3. If it is a stranger's voice, the pipeline resets, and this component goes back to sleep.
Stage 3 (Cloud-Bound): The "Cloud Transcription" Stream (STT)
Component: WebSocket Client & Streaming STT API.
State: Off. This component is activated only when triggered by Stage 2.
Job: The client establishes a secure WebSocket connection to a low-latency, streaming STT cloud provider. It streams the verified user's speech bytes in real-time and receives text transcripts back as JSON messages.9
Termination: If Stage 1 (VAD) detects silence for a predetermined duration (e.g., 2-3 seconds), it signals Stage 3 to terminate the cloud connection and close the WebSocket. The entire system then returns to its low-power Stage 1 listening state.
This gated design directly solves the project's primary risks. Running a full Speaker Verification model (Stage 2) 24/7 on every audio frame would be a significant battery drain.11 Running the VAD (Stage 1) is massively more efficient. This ensures that for the vast majority of the day—when there is silence, noise, or other people talking—the application is only running the "cheap" Stage 1 component. The "expensive" CPU-intensive (Stage 2) and "expensive" network-intensive (Stage 3) components are only activated precisely when the enrolled user is speaking.

II. The Android Client: Surviving the "Always-On" Environment


A. Building the Foundation: The Microphone Foreground Service (FGS)

The "Always-On" requirement cannot be met with standard Android background services. Android's power-saving features like Doze and App Standby are designed to halt all background operations, even those run by WorkManager, which has a minimum interval of 15 minutes.13 Therefore, 24/7 continuous audio capture is impossible without a Foreground Service (FGS).
The Mandate for a Foreground Service (FGS)
An FGS is a service that performs operations noticeable to the user and must display a persistent system notification.15 This is a non-negotiable requirement from the Android OS to make the user explicitly aware that the application is active and consuming resources. For this use case, it is the only viable path.
Manifest Declaration: FGS Types and Permissions
Since Android 9 (API 28), only applications running in the foreground or as an FGS are permitted to capture audio input.17 Starting in Android 10 (API 29), the android:foregroundServiceType attribute was introduced in the manifest.15
As of Android 14 (API 34), specifying the correct FGS type is mandatory. Failure to declare an appropriate type will result in the system throwing a MissingForegroundServiceTypeException when startForeground() is called.19
For this project, the following declarations in AndroidManifest.xml are required:

XML


<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<application...>
   ...
    <service
        android:name=".services.SelectiveSpeakerService"
        android:foregroundServiceType="microphone"
        android:exported="false">
    </service>
</application>


The FGS Timeout Trap
It is critical to understand that different FGS types have different lifecycle limitations. For example, services of type dataSync and mediaProcessing are limited by the system to a total of 6 hours in any 24-hour period.21 If an app exceeds this, the system throws a ForegroundServiceStartNotAllowedException.
The microphone type, like location, is intended for long-running, user-aware sessions (e.g., navigation, fitness tracking).19 The documentation does not list a specific timeout for this type, which implies the Android OS will permit it to run indefinitely, provided the persistent notification is active. The real threat of termination comes not from the Android OS, but from the OEM's proprietary battery-saving software.1
Service Lifecycle and Resilience
To ensure the service attempts to restart if killed, its onStartCommand method must return START_STICKY.23 This flag tells the system to recreate the service after a crash or OS-level kill, but it does not re-deliver the last intent.
However, START_STICKY is not a guarantee. On many customized Android ROMs (e.g., Samsung, Huawei, Xiaomi), this flag is frequently ignored or non-functional.26 To achieve true persistence, the application must also implement a BroadcastReceiver that listens for the android.intent.action.BOOT_COMPLETED action. This receiver's only job is to re-launch the FGS after the device finishes restarting.13
Audio Capture: AudioRecord vs. MediaRecorder
This is a critical implementation choice. The application must use AudioRecord.
MediaRecorder is a high-level API designed to capture and encode audio/video to a file (e.g., creating an.aac or.mp4 file).27 This is useless for real-time streaming analysis.
AudioRecord is a low-level API that provides a direct, raw stream of audio bytes (typically 16-bit PCM) from the microphone's hardware buffer.29
The entire 3-stage pipeline (VAD, SV, and STT) is designed to consume this raw PCM byte stream.5 AudioRecord is the correct tool for this job.
Audio Routing: Built-in Mic vs. Bluetooth (SCO)
A post-MVP consideration is how the app handles audio input switching, such as when a user connects a Bluetooth headset. While Android can manage some of this automatically 33, a robust FGS will need to use AudioManager to manage audio focus. This includes detecting the connection of a SCO (Speech Communication Off-chip) device and programmatically routing audio using methods like AudioManager.startBluetoothSco() and AudioManager.setBluetoothScoOn(true) to capture from the headset's microphone instead of the device's built-in mic.34

B. Confronting the "Samsung Problem": An S6 Lite Mitigation Strategy

The single greatest risk to this project's "Always-On" requirement is the choice of a Samsung Galaxy Tab S6 Lite as the test device.37 Samsung's One UI (the software layer on top of Android) 39 is internationally recognized by developers as one of the most aggressive platforms for killing background processes, far exceeding the behavior of stock Android.1
Analysis of One UI's "Optimizations"
The "Don't Kill My App" (DKMA) initiative, which tracks these OEM issues, consistently ranks Samsung as a top offender.1 The problem lies in a proprietary, multi-layered system that operates in addition to standard Android "Battery Optimization" (Doze).11
The key terms to understand are 1:
Sleeping apps: A default state. Apps on this list may have background activities deferred or limited.
Deep sleeping apps: The "kill list." Apps on this list will never run in the background. They are force-stopped and will only work when the user manually opens them. Samsung's OS automatically adds apps it deems "unused" to this list, breaking all FGS-based functionality.
Never sleeping apps: The "whitelist." This is the only place the application can live and reliably survive for 24/7 operation.
The User-Facing Solution: A Mandatory Onboarding Flow
The application cannot simply be installed; it must be onboarded. It is not enough to ask for the standard REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission.48 This is necessary but wildly insufficient on Samsung.
The application must programmatically detect it is running on a Samsung device (e.g., Build.MANUFACTURER == "samsung").50 On its first run, it must present a multi-step, unavoidable guide that forces the user to manually add the app to the "Never sleeping apps" list.46
This process is complicated by a critical, non-obvious One UI flaw:
A developer's first instinct is to ask the user to add the app to Settings -> Apps -> YourApp -> Battery -> Unrestricted.
However, multiple user reports confirm that if an app is already set to "Unrestricted" (the stock Android setting), it may not appear in the list of apps that can be added to Samsung's proprietary "Never sleeping apps" list.51
The convoluted workaround is: The user must first set the app to "Optimized," then add it to "Never sleeping apps," and then (optionally) set it back to "Unrestricted."
This is a developer and support nightmare.53 The application's onboarding flow must minimize this friction by using Intents to open these specific, hidden settings pages programmatically.
Table 1: Key Intent Actions for Samsung (One UI) Battery Settings
This table provides the specific Intent actions and ComponentName strings required to build this critical onboarding flow. This saves the user from navigating the 5-click-deep, confusing One UI settings menus.

Setting to Open
Intent Action / Component
Purpose
Source(s)
Standard Android Battery Optimization
Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
Opens the stock Android "Battery optimization" list. This is the baseline "Doze" whitelisting.
48
Samsung Power Usage Summary
Intent.ACTION_POWER_USAGE_SUMMARY
Opens the main battery stats page. A general-purpose fallback.
50
Samsung Battery Settings (Primary)
ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") (For Android N+)
Opens the main Samsung Battery "Device Care" page. This is the entry point to the "Background usage limits" and "Never sleeping apps" list.
50
Samsung Battery Settings (Legacy)
ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity") (For Android L-N)
Fallback for older Samsung devices.
50
(Other OEM Example)
ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
An example for Huawei, demonstrating that this OEM-specific Intent targeting is a common, necessary practice.
50


III. The On-Device Engine: Implementing "Selective Speaker" (Stages 1 & 2)


A. Stage 1: Low-Power Voice Activity Detection (VAD)

The "Why": Solving the Battery Drain Problem
An FGS with the microphone permission is allowed to run, but if it constantly spins the CPU at 100%, the OS (or the user) will kill it for excessive battery consumption.11 The VAD is the "sleep" state of the pipeline. It is the cheapest possible way to answer the question, "Is this sound human speech?".6 This component must run on-device; streaming 24/7 to a cloud VAD would be even more costly in terms of both battery and data.
VAD Model Comparison & Implementation
There are two primary, battle-tested options for on-device VAD.
Option 1: WebRTC VAD (The Classic)
This is the VAD used by Google for WebRTC. It is a Gaussian Mixture Model (GMM), not a neural network, which makes it extremely fast and lightweight.56
It is known for being aggressive in its classification, meaning it is excellent at rejecting noise but can sometimes be too quick to classify speech as silence.57
An excellent, pre-compiled Android library is available (android-vad on GitHub) that wraps the WebRTC VAD.56
Implementation Example:
Kotlin
// Example based on 
val vad = Vad.builder()
   .setSampleRate(SampleRate.SAMPLE_RATE_16K) // Must match AudioRecord config
   .setFrameSize(FrameSize.FRAME_SIZE_320)     // Must match AudioRecord buffer size
   .setMode(Mode.VERY_AGGRESSIVE)             // Tunable (0-3)
   .build()

// In the AudioRecord read-loop...
val audioDataChunk: ByteArray = //... read from AudioRecord
val isSpeech = vad.isSpeech(audioDataChunk)
if (isSpeech) {
    // Trigger Stage 2 (Speaker Verification)
}


Option 2: Silero-VAD (The Newcomer)
This is a lightweight, pre-trained deep neural network (DNN).59
It claims superior accuracy over WebRTC VAD, especially in noisy environments.5
It is still extremely fast and efficient: the model is ~1.8MB, and it can process a 30ms audio chunk in ~1ms on a modern CPU.5
The same android-vad library 56 that wraps WebRTC VAD also supports Silero VAD. This allows for easy swapping and benchmarking.
Action: The recommended path is to use the android-vad library 56 and begin with the Silero-VAD implementation, as it represents the current state-of-the-art for lightweight, accurate VAD. The performance can be benchmarked against the WebRTC VAD during the PoC (Phase 2).

B. Stage 2: On-Device Speaker Verification (The "Selective" Filter)

The Critical Distinction: Verification vs. Identification vs. Diarization
This is a common point of confusion. The project's success depends on choosing the correct technology.
Speaker Diarization: Answers "Who spoke when?" It clusters speakers into anonymous labels like "Speaker 1" and "Speaker 2".61 This is what Google's STT API offers as a feature.63 This is not the required functionality.
Speaker Identification: Answers "Which of these N known speakers is this?" This is a 1:N search, like a smart-home device recognizing different family members.8
Speaker Verification: Answers "Is this person User X?" This is a 1:1 match, an authentication task.8
The PRD "Selective Speaker" implies Speaker Verification. The app must verify that the voice detected by VAD matches a single, pre-enrolled user.
Technology Deep Dive: SDKs vs. DIY
Option A (Recommended for PoC): Vosk (Open-Source)
What it is: Vosk is a full-stack, open-source speech toolkit that runs 100% offline.66
Pros: It is free, has an Android demo 68, and explicitly supports speaker identification/verification.66
Cons: The models are larger (a small model is ~50Mb).66 The documentation for speaker verification is less "out-of-the-box" than for simple transcription.
The Enrollment Process: Vosk's speaker recognition is based on extracting "X-vectors," which are mathematical "voiceprints".70 The enrollment process is "DIY":
Load a SpeakerModel during the recognizer's initialization.71
Have the user speak several phrases in a quiet "Enrollment" activity.
Process this audio. The resulting JSON from recognizer.Result() will contain a 'spk' field (the X-vector).70
The application must save this X-vector (a float array) to persistent storage as the "enrolled" voiceprint.
During verification (Stage 2), the app extracts the X-vector of the new speech and calculates the cosine distance 70 against the saved vector. If the distance is below a pre-set threshold, it is a match.
Option B (Recommended for Production): Picovoice Eagle
What it is: A commercial, on-device Speaker Verification SDK.7
Pros: It is purpose-built for this exact 1:1 verification task.8 The API is extremely simple, and the model is highly accurate and language-agnostic.72
Cons: It is a commercial product. The free tier is for non-commercial use only.74 The "Foundation" plan, for small startups, is listed at $6,000/year.75 This is a business-model decision.
Option C (The "Computer Engineering" Route): TensorFlow Lite (DIY)
What it is: Using TFLite 76 or PyTorch Mobile (ExecuTorch) 77 to run a custom-trained or open-source speaker verification model.
Pros: Full control, zero licensing fees.
Cons: This is a massive undertaking, effectively a research project in itself. It requires finding a lightweight model 78, converting it to .tflite format 81, writing all the audio pre-processing (MFCC/spectrogram generation) 76, and integrating it using the TFLite Audio Task Library.83 This is not a starting point; it is the path to building a product like Picovoice Eagle, not using one.
PoC-Level Benchmarking (The S6 Lite)
The Galaxy Tab S6 Lite has an Octa-Core CPU 84 and its performance is described as "pretty alright" but with "noticeable lag" at times.38 This makes on-device performance testing critical.
Before committing to a model, its performance must be validated.
Action: Use the Android Studio Profiler 85 (or TFLite's own benchmark tools 86) to measure the CPU and Memory usage of the Foreground Service process.
Success Criteria:
Stage 1 (VAD only): What is the CPU usage while the app is in its "listening" state? This should be < 1-2% to be sustainable.
Stage 2 (VAD + SV): When speech is detected, the CPU will spike. This is expected. How long does it take to get a "match/no-match" result? This must be faster than real-time.
Battery: What is the total % battery drain over an 8-hour period? This can be monitored via Samsung's "Battery and device care" settings 87 or by observing test device logs.90 This is the ultimate pass/fail metric for the "Always-On" requirement.

IV. The Cloud Pipeline: "Cloud Transcription" (Stage 3)


A. Analyzing the User's Candidates: Orchestration vs. Direct Streaming

A core misunderstanding in the initial query is the function of the evaluated services. The listed platforms (Vapi, LiveKit, Agora) are the wrong class of service for this project's simple, one-way transcription requirement.
Vapi ("very powerful"): This is a high-level, conversational AI agent platform.91 It is designed to orchestrate a full, duplexed conversation: STT -> LLM -> TTS. It is built to talk back.94 This is massive overkill for a one-way transcription task and introduces unnecessary cost and latency.
LiveKit ("flexible"): This is an open-source, WebRTC-based transport platform.95 It is for building multi-participant audio/video "rooms," like a custom Zoom or Clubhouse.97 Using it for a 1-way stream requires setting up a complex Selective Forwarding Unit (SFU) server 96, which is like building a 10-lane highway for a single bicycle.
Agora ("low latency"): This is a direct, commercial competitor to LiveKit.99 It is a full Real-Time Communication (RTC) stack. While it does offer an integrated STT service 101, it locks the application into its entire, expensive ecosystem.
Recommendation: A Simpler, Better Architecture
The PRD requires a one-way stream from a single device to a cloud service. The simplest, cheapest, and lowest-latency architecture is a direct WebSocket connection from the Android FGS to a pure streaming STT API.9
This approach removes the entire orchestration/RTC-server middleman (Vapi, LiveKit), reducing complexity, cost, and points of failure. The implementation involves using AudioRecord to read PCM bytes and sending them directly over a WebSocket, using a standard client like OkHttp.10

B. The Core Service: Low-Latency Streaming STT APIs

The real decision for Stage 3 is selecting the best pure STT API.
De-coding the "100ms" Target (Cartesia)
The "cartesia - 100ms?" note is a crucial starting point. Research confirms that Cartesia's TTS (Text-to-Speech) model, Sonic, achieves ultra-low latency of 40-90ms.2 This is for generating speech (voice-out), not transcribing it (voice-in).109
This is a critical misunderstanding. However, this mistake correctly identifies a key vendor. Cartesia has recently launched a streaming STT product called Ink-Whisper.4
Cartesia's benchmarks for Ink-Whisper introduce a new, more relevant metric: "Time-to-Complete-Transcript" (TTCT). This is the latency from when the user stops talking to when the final, complete transcript is received. Cartesia's P90 (90th percentile) TTCT for Ink-Whisper is 98ms.4
Therefore, while the initial reasoning was flawed (TTS vs. STT), the conclusion was correct: Cartesia is a provider that is advertising a sub-100ms latency metric relevant to this project.
API Candidate Analysis (The "100ms" Bake-Off)
The decision for the PoC should be between the new, low-latency-focused players.
Candidate 1: Cartesia (Ink-Whisper)
Latency: P90 TTCT of 98ms. This is the fastest response latency benchmarked.4
Accuracy: A variant of OpenAI's Whisper, re-architected for real-time streaming.4 Benchmarks show it is competitive, performing well on disfluencies (fillers) but slightly weaker on proper nouns than Deepgram.4
Cost: Extremely low, priced at $0.13/hr on their Scale plan 108 or $0.18/hr via LiveKit's marketplace.114
Candidate 2: Deepgram (Nova-3)
Latency: Deepgram advertises sub-300ms latency.115 The same Cartesia benchmark places its P90 TTCT at 109ms 4, which is still extremely fast and likely imperceptible to a user.
Accuracy: Widely considered the enterprise-grade SOTA (State of the Art).116 They claim a 30% lower Word Error Rate (WER) than competitors 115, and their models are heavily optimized for enterprise use cases like background noise and jargon.4
Cost: Higher, at $0.462/hr.114
Candidate 3: AssemblyAI (Universal-Streaming)
Latency: The Cartesia benchmark shows a very high TTCT of 829ms (P90).4 This seems anomalous compared to other claims of 300ms P50 latency 117 and may reflect a different "end-of-speech-detection" configuration.
Accuracy: A top-tier contender, often trading blows with Deepgram in accuracy benchmarks.118
Cost: Low, at $0.150/hr.114
Table 2: Streaming STT API Head-to-Head (PoC Phase 3)
This table synthesizes the streaming STT market for a one-way, low-latency use case.

Provider
Model
Key Latency Metric
P90 Latency (ms)
Cost ($/hr)
Source(s)
Cartesia
Ink-Whisper
Time-to-Complete-Transcript (TTCT)
98
$0.13 - $0.18
4
Deepgram
Nova-3
Time-to-Complete-Transcript (TTCT)
109
$0.46
4
AssemblyAI
Universal-Streaming
Time-to-Complete-Transcript (TTCT)
829 *
$0.15
4
Google / AWS
Standard / Transcribe
(Not benchmarked for TTCT)
>300-700ms
(Varies)
120
(*Note: The 829ms TTCT for AssemblyAI in the Cartesia benchmark 4 is high and may not reflect an optimized configuration. This warrants independent testing during the PoC.)












V. The Path Forward: A Phased Proof-of-Concept (PoC) Plan

The following 4-phase plan provides the "guide to go forward." It is structured to de-risk the project by testing the riskiest assumptions first. A common engineering mistake is to build the "coolest" part first (the AI or the cloud streaming). The correct architectural approach is to first prove the project's foundation is not built on sand.
The Riskiest-Assumption-First Model:
Highest Risk: FGS Survival. Can the service actually run 24/7 on a Samsung S6 Lite?.1 If the answer is no, the "Always-On" PRD is not feasible.
Second Risk: Battery Life. Can the 3-stage pipeline run without making the device unusable?.11
Third Risk: Cloud Latency. Can the 100ms target be met in a real-world scenario?.4
This PoC plan validates these risks in order.

A. Phase 1: Validate FGS Survival (The "Samsung Problem")

Goal: Test 72-hour+ survival of a minimal FGS on the Samsung S6 Lite.
Build: A "Heartbeat Service."
Create a minimal ForegroundService with android:foregroundServiceType="microphone" and the required permissions.20
Return START_STICKY from onStartCommand.25
Implement a BOOT_COMPLETED receiver to restart the service on reboot.13
The service does no AI and no audio capture. It only runs a simple Timer that writes (Timestamp, "Heartbeat") to a local text file (in app-specific storage) every 60 seconds.
Test:
Install the app on the S6 Lite.
Manually perform the entire One UI whitelisting process from Section II.B. Use the Intents 50 to open the settings pages. Add the app to "Never sleeping apps".1
Unplug the device from USB. Turn the screen off.
Wait 72 hours. Do not interact with the device.
After 72 hours, unlock the device and retrieve the log file.
Pass/Fail: If the log has continuous heartbeats for 72 hours, the project is viable. If the log stops after 4, 12, or 24 hours, the "Always-On" PRD is not feasible on this hardware without rooting the device.

B. Phase 2: Validate On-Device Pipeline Performance (The "Selective" Filter)

Goal: Test the accuracy and, most importantly, the battery cost of the on-device VAD + Verification pipeline.
Build:
Add the android-vad library to the "Heartbeat Service".56
Add AudioRecord and begin capturing audio.
Implement Stage 1 (VAD). In the log, now write "VAD: Speech Detected" when triggered.
Add the vosk-android library.68
Build a minimal "Enrollment" Activity to capture and save the user's X-vector (voiceprint).70
Implement Stage 2 (SV). When VAD triggers, run the SV model. Log "SV: User Verified" or "SV: Stranger Detected".
Test:
Use the app for an 8-hour workday. Keep the device unplugged. Talk near it. Have others talk near it.
Check the logs: Does VAD correctly trigger on speech and ignore noise? Does SV correctly accept the enrolled user and reject others?
Critical: Use Samsung's "Battery and device care" settings 87 or the Android Studio Profiler 85 to measure the app's total battery drain over the 8-hour period.
Pass/Fail: Is the battery drain acceptable (e.g., < 15-20% in 8 hours)? Is the verification accuracy >95%?

C. Phase 3: Validate End-to-End Cloud Latency (The "Cloud" Feature)

Goal: Test the real-world, end-to-end latency of the selective transcription and validate the sub-100ms TTCT target.
Build:
Implement Stage 3. When Stage 2 ("SV: User Verified") passes, the service opens a WebSocket connection.
Use a robust WebSocket client (like OkHttp's) to stream the raw PCM bytes from AudioRecord.10
Action: Test Cartesia Ink-Whisper first, as its benchmarks 4 are most aligned with the project's latency goal.
Test:
In the FGS, speak a short sentence ("This is a test") and stop.
Log a timestamp for T1 (when VAD detects the end-of-speech).
Log a timestamp for T2 (when the final transcript is received from the WebSocket).
Calculate the TTCT = T2 - T1. Repeat this test 50-100 times.
Pass/Fail: Is the P90 (90th percentile) of this TTCT < 150ms? Is the transcription accurate?

D. Phase 4: The Minimum Viable Product (MVP)

Goal: Combine the validated components from Phases 1-3 into a user-facing application.126
Build:
A clean, simple, and unavoidable UI for the "Samsung Onboarding" guide (Section II.B).
A robust UI for Speaker Enrollment (Section III.B), with clear instructions for the user to provide voice samples.73
A simple Activity with a RecyclerView to View Transcripts.
A settings page to manage the FGS (Start/Stop) and view logs.
By following this phased approach, the project moves from a high-risk vision to a de-risked, validated MVP. The core platform risks (FGS survival, battery) and technology risks (AI accuracy, cloud latency) are identified and solved before significant development time is invested in UI. This is the recommended "setup and guide to go forward."
Works cited
Samsung | Don't kill my app!, accessed November 10, 2025, https://dontkillmyapp.com/samsung
Customers | Cartesia, accessed November 10, 2025, https://cartesia.ai/customers
Cartesia Vs ElevenLabs, accessed November 10, 2025, https://cartesia.ai/vs/cartesia-vs-elevenlabs
Introducing Ink: speech-to-text models for real-time conversation - Cartesia, accessed November 10, 2025, https://cartesia.ai/blog/introducing-ink-speech-to-text
SileroVAD : Machine Learning Model to Detect Speech Segments - Medium, accessed November 10, 2025, https://medium.com/axinc-ai/silerovad-machine-learning-model-to-detect-speech-segments-e99722c0dd41
WebRTC Voice Activity Detection: Real-Time Speech Detection in 2025 - VideoSDK, accessed November 10, 2025, https://www.videosdk.live/developer-hub/webrtc/webrtc-voice-activity-detection
Eagle Speaker Recognition | Android Quick Start - Picovoice, accessed November 10, 2025, https://picovoice.ai/docs/quick-start/eagle-android/
State of Speaker Recognition in 2023 - Picovoice, accessed November 10, 2025, https://picovoice.ai/blog/state-of-speaker-recognition/
Transcribe streaming audio | AssemblyAI | Documentation, accessed November 10, 2025, https://www.assemblyai.com/docs/getting-started/transcribe-streaming-audio
Stream Live Audio from Client to Server Using WebSocket & OkHttp - Canopas, accessed November 10, 2025, https://canopas.com/android-send-live-audio-stream-from-client-to-server-using-websocket-and-okhttp-client-ecc9f28118d9
Optimize for Doze and App Standby | App quality - Android Developers, accessed November 10, 2025, https://developer.android.com/training/monitoring-device-state/doze-standby
How to reduce the battery drain in foreground service based application in android kotlin, accessed November 10, 2025, https://support.google.com/android/thread/339689958/how-to-reduce-the-battery-drain-in-foreground-service-based-application-in-android-kotlin?hl=en
How to keep my android app alive in background for 24/7 continuous sensor data collection, accessed November 10, 2025, https://stackoverflow.com/questions/54124631/how-to-keep-my-android-app-alive-in-background-for-24-7-continuous-sensor-data-c
Background Execution Limits - Android Developers, accessed November 10, 2025, https://developer.android.com/about/versions/oreo/background
Guide to Foreground Services on Android 14 - Medium, accessed November 10, 2025, https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-9d0127dc8f9a
Services overview | Background work - Android Developers, accessed November 10, 2025, https://developer.android.com/develop/background-work/services
What's the point of a foreground service to record audio in Android 11? - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/68177773/whats-the-point-of-a-foreground-service-to-record-audio-in-android-11
Sharing audio input | Android media - Android Developers, accessed November 10, 2025, https://developer.android.com/media/platform/sharing-audio-input
Guide to Foreground Services on Android 14 - Medium, accessed November 10, 2025, https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-14-9d0127dc8f9a
Foreground service types are required - Android Developers, accessed November 10, 2025, https://developer.android.com/about/versions/14/changes/fgs-types-required
Foreground service types | Background work - Android Developers, accessed November 10, 2025, https://developer.android.com/develop/background-work/services/fgs/service-types
Foreground service timeouts | Background work - Android Developers, accessed November 10, 2025, https://developer.android.com/develop/background-work/services/fgs/timeout
Android foreground service gets killes after 24 hours - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/64832968/android-foreground-service-gets-killes-after-24-hours
Minimal android foreground service killed on high-end phone - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/49637967/minimal-android-foreground-service-killed-on-high-end-phone
Foreground Services in Android - by Jeet Dholakia - Medium, accessed November 10, 2025, https://medium.com/@engineermuse/foreground-services-in-android-e131a863a33d
android - START_STICKY not working - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/33710694/start-sticky-not-working
Audio Recorder in Android with Example - GeeksforGeeks, accessed November 10, 2025, https://www.geeksforgeeks.org/android/audio-recorder-in-android-with-example/
MediaRecorder overview | Android media - Android Developers, accessed November 10, 2025, https://developer.android.com/media/platform/mediarecorder
Recording audio continuously and updating the view - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/31023682/recording-audio-continuously-and-updating-the-view
Live Audio Transcription for Android Apps | ExpertAppDevs - Medium, accessed November 10, 2025, https://medium.com/@expertappdevs/audio-calling-live-transcript-in-android-for-android-developers-2e9ec095ad3d
Audio recording | Connectivity - Android Developers, accessed November 10, 2025, https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording
Real-Time Audio Transcription API: How to Turn Speech to Text During Live Conferencing, accessed November 10, 2025, https://fishjam.io/blog/real-time-audio-transcription-api-how-to-turn-speech-to-text-during-live-conferencing-f77e2ff3f4de
Set up audio switch on your Android device - Google Help, accessed November 10, 2025, https://support.google.com/android/answer/12375846?hl=en
how to switch audio input between inbuilt mic and headset mic after a bluetooth headset is connected in android? - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/42782185/how-to-switch-audio-input-between-inbuilt-mic-and-headset-mic-after-a-bluetooth
how to switch audio output programatically in android 10? - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/60859938/how-to-switch-audio-output-programatically-in-android-10
[Question] How to set output audio device? · Issue #39 · ryanheise/audio_session - GitHub, accessed November 10, 2025, https://github.com/ryanheise/audio_session/issues/39
Make your mark. - Samsung, accessed November 10, 2025, https://image-us.samsung.com/SamsungUS/samsungbusiness/pdfs/datasheet/Galaxy_Tab_S6_LiteDatasheet.pdf
Samsung Galaxy Tab S6 Lite [2024] - Blew away my expectations, brief review. - Reddit, accessed November 10, 2025, https://www.reddit.com/r/GalaxyTab/comments/1fdu1qh/samsung_galaxy_tab_s6_lite_2024_blew_away_my/
One UI 7 has destroyed background app performance : r/Android - Reddit, accessed November 10, 2025, https://www.reddit.com/r/Android/comments/1kf8yd2/one_ui_7_has_destroyed_background_app_performance/
One UI 7 New Features | Samsung CA, accessed November 10, 2025, https://www.samsung.com/ca/one-ui/features/
One UI - Design Samsung, accessed November 10, 2025, https://design.samsung.com/global/contents/one-ui/download/oneui_design_guide_eng.pdf
Foreground Location service shuts down automatically in oreo 8.1 ... : r/androiddev - Reddit, accessed November 10, 2025, https://www.reddit.com/r/androiddev/comments/au8t0f/foreground_location_service_shuts_down/
Don't kill my app! | Hey Android vendors, don't kill my app!, accessed November 10, 2025, https://dontkillmyapp.com/
App Standby Buckets | App quality - Android Developers, accessed November 10, 2025, https://developer.android.com/topic/performance/appstandby
Sleeping apps on your Galaxy device - Samsung, accessed November 10, 2025, https://www.samsung.com/us/support/answer/ANS10003442/
How to stop apps from sleeping on Android devices - Android Police, accessed November 10, 2025, https://www.androidpolice.com/prevent-apps-from-sleeping-in-the-background-on-android/
(Solved) Android 11 Samsung "Deep Sleep" Wi-Fi Connectivity Issue - SecureW2, accessed November 10, 2025, https://www.securew2.com/blog/samsung-deep-sleep-wi-fi-solved
Android M startActivity battery optimization - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/41596509/android-m-startactivity-battery-optimization
Open Battery Optimization activity of specific app in device settings - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/56641844/open-battery-optimization-activity-of-specific-app-in-device-settings
android open battery settings programically - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/48281421/android-open-battery-settings-programically
Unable to add app to "Never sleeping apps" list - Mobile - Samsung Developer Forums, accessed November 10, 2025, https://forum.developer.samsung.com/t/unable-to-add-app-to-never-sleeping-apps-list/11663
How to put wear os app on Never Sleeping Apps? It is not on the list to add to it. - Reddit, accessed November 10, 2025, https://www.reddit.com/r/Galaxy_S20/comments/q0446m/how_to_put_wear_os_app_on_never_sleeping_apps_it/
Don't kill my app! - Stop Crippling Background Apps (solutions for most vendors) : r/Android, accessed November 10, 2025, https://www.reddit.com/r/Android/comments/ac6dwe/dont_kill_my_app_stop_crippling_background_apps/
Samsung "App optimisation" feature kills background applications after 3 days, accessed November 10, 2025, https://stackoverflow.com/questions/36850109/samsung-app-optimisation-feature-kills-background-applications-after-3-days
Disabling foreground and background activities to improve battery life. : r/oneplus - Reddit, accessed November 10, 2025, https://www.reddit.com/r/oneplus/comments/11q55sc/disabling_foreground_and_background_activities_to/
com.github.gkonovalov.android-vad » webrtc - Maven Repository, accessed November 10, 2025, https://mvnrepository.com/artifact/com.github.gkonovalov.android-vad/webrtc
gkonovalov/android-vad: Android Voice Activity Detection (VAD) library. Supports WebRTC VAD GMM, Silero VAD DNN, Yamnet VAD DNN models. - GitHub, accessed November 10, 2025, https://github.com/gkonovalov/android-vad
com.cloudflare.realtimekit.android-vad:webrtc:2.0.9-cf.3 - Maven Central, accessed November 10, 2025, https://central.sonatype.com/artifact/com.cloudflare.realtimekit.android-vad/webrtc/2.0.9-cf.3
Silero Voice Activity Detector - PyTorch, accessed November 10, 2025, https://pytorch.org/hub/snakers4_silero-vad_vad/
Silero VAD: pre-trained enterprise-grade Voice Activity Detector - GitHub, accessed November 10, 2025, https://github.com/snakers4/silero-vad
Speaker diarization vs speaker recognition - what's the difference? - AssemblyAI, accessed November 10, 2025, https://www.assemblyai.com/blog/speaker-diarization-vs-recognition
Speaker Diarization vs Speaker Identification - Picovoice, accessed November 10, 2025, https://picovoice.ai/blog/speaker-diarization-vs-speaker-recognition-identification/
Detect different speakers in an audio recording | Cloud Speech-to-Text, accessed November 10, 2025, https://docs.cloud.google.com/speech-to-text/docs/multiple-voices
On-device speaker identification for Digital Television (DTV) - Arm Community, accessed November 10, 2025, https://developer.arm.com/community/arm-community-blogs/b/ai-blog/posts/on-device-speaker-identification-for-dtvs
Automatic speaker recognition (ASR): identification, verification and diarization - Gladia, accessed November 10, 2025, https://www.gladia.io/blog/an-introduction-to-asr-speaker-recognition-identification-verification-and-diarization
alphacep/vosk-api: Offline speech recognition API for Android, iOS, Raspberry Pi and servers with Python, Java, C# and Node - GitHub, accessed November 10, 2025, https://github.com/alphacep/vosk-api
VOSK Offline Speech Recognition API - Alpha Cephei, accessed November 10, 2025, https://alphacephei.com/vosk/
Offline speech recognition on Android with VOSK - Alpha Cephei, accessed November 10, 2025, https://alphacephei.com/vosk/android
Vosk Installation - Alpha Cephei, accessed November 10, 2025, https://alphacephei.com/vosk/install
Vosk Speaker Recognition - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/67386930/vosk-speaker-recognition
Android documentation on speaker recognition · Issue #522 · alphacep/vosk-api - GitHub, accessed November 10, 2025, https://github.com/alphacep/vosk-api/issues/522
Eagle Speaker Recognition & Real-Time Voice Identification - Picovoice, accessed November 10, 2025, https://picovoice.ai/platform/eagle/
Android Speech Recognition - Picovoice, accessed November 10, 2025, https://picovoice.ai/blog/android-speech-recognition/
Pricing: Plans for on-device voice recognition - Picovoice, accessed November 10, 2025, https://picovoice.ai/pricing/
Foundation Plan - Picovoice, accessed November 10, 2025, https://picovoice.ai/checkout/
“Hello,” from the Mobile Side: TensorFlow Lite in Speaker Recognition | by Alibaba Tech, accessed November 10, 2025, https://medium.com/hackernoon/hello-from-the-mobile-side-tensorflow-lite-in-speaker-recognition-7519b18c2646
Welcome to the ExecuTorch Documentation - PyTorch, accessed November 10, 2025, https://docs.pytorch.org/executorch/index.html
A Light Weight Model for Active Speaker Detection, accessed November 10, 2025, https://openaccess.thecvf.com/content/CVPR2023/papers/Liao_A_Light_Weight_Model_for_Active_Speaker_Detection_CVPR_2023_paper.pdf
CoLMbo: Speaker Language Model for Descriptive Profiling - arXiv, accessed November 10, 2025, https://arxiv.org/html/2506.09375v1
Wenhao-Yang/SpeakerVerifiaction-pytorch: Speaker Verification using Pytorch - GitHub, accessed November 10, 2025, https://github.com/Wenhao-Yang/SpeakerVerifiaction-pytorch
Retrain a speech recognition model with TensorFlow Lite Model Maker | Google AI Edge, accessed November 10, 2025, https://ai.google.dev/edge/litert/libraries/modify/speech_recognition
Recognize Flowers with TensorFlow Lite on Android, accessed November 10, 2025, https://developer.android.com/codelabs/recognize-flowers-with-tensorflow-on-android
Create a basic app for audio classification - Google for Developers, accessed November 10, 2025, https://developers.google.com/codelabs/tflite-audio-classification-basic-android
Galaxy Tab S6 Lite (LTE, 2024) - Samsung, accessed November 10, 2025, https://www.samsung.com/levant/tablets/galaxy-tab-s/galaxy-tab-s6-lite-mint-64gb-sm-p625nlgamea/
Configure on-device developer options | Android Studio, accessed November 10, 2025, https://developer.android.com/studio/debug/dev-options
Performance measurement | Google AI Edge, accessed November 10, 2025, https://ai.google.dev/edge/litert/models/measurement
Galaxy Battery - Optimization - Samsung, accessed November 10, 2025, https://www.samsung.com/us/support/galaxy-battery/optimization/
Samsung Galaxy Tip - How to Check the Battery Health - YouTube, accessed November 10, 2025, https://www.youtube.com/watch?v=pvgZvh5UDrw
SAMSUNG Galaxy Tab S6 Lite - How to check battery health via ampere app - YouTube, accessed November 10, 2025, https://www.youtube.com/watch?v=gZlYomRkoZs
Tab S6 lite battery life : r/samsung - Reddit, accessed November 10, 2025, https://www.reddit.com/r/samsung/comments/jodypj/tab_s6_lite_battery_life/
Vapi vs Elevenlabs Conversational AI – Here's our pick - Ringly.io, accessed November 10, 2025, https://www.ringly.io/comparison/vapi-vs-elevenlabs-conversational-ai
Vapi - Build Advanced Voice AI Agents, accessed November 10, 2025, https://vapi.ai/
VAPI vs Bland - Phonely AI, accessed November 10, 2025, https://www.phonely.ai/blogs/vapi-vs-bland-which-voice-ai-is-the-best
Ranking The Best AI Voice Agent Platforms (2025) - My Framer Site - Artilo AI, accessed November 10, 2025, https://www.artiloai.com/blog/ranking-horizontal-voice-ai-platforms
Use Case: Livestreaming - LiveKit, accessed November 10, 2025, https://livekit.io/use-cases/livestreaming
LiveKit vs Vapi: which voice AI framework is best in 2025? | Modal Blog, accessed November 10, 2025, https://modal.com/blog/livekit-vs-vapi-article
Text and transcriptions - LiveKit docs, accessed November 10, 2025, https://docs.livekit.io/agents/build/text/
Build an AI agent with LiveKit for real-time Speech-to-Text | Full Python tutorial - YouTube, accessed November 10, 2025, https://www.youtube.com/watch?v=A400nCCZlK4
Real-Time Speech-To-Text Product overview | Agora Docs, accessed November 10, 2025, https://docs.agora.io/en/real-time-stt/overview/product-overview
Real-Time Speech to Text - Agora, accessed November 10, 2025, https://www.agora.io/en/products/speech-to-text/
Introducing Agora's Conversational AI Engine | by Hermes - Medium, accessed November 10, 2025, https://medium.com/agora-io/introducing-agoras-conversational-ai-engine-756b457f742d
Agora Releases Real-Time Transcription API - XR Today, accessed November 10, 2025, https://www.xrtoday.com/mixed-reality/agora-releases-real-time-transcription-api/
Streaming AI Responses with WebSockets, SSE, and gRPC: Which One Wins? - Medium, accessed November 10, 2025, https://medium.com/@pranavprakash4777/streaming-ai-responses-with-websockets-sse-and-grpc-which-one-wins-a481cab403d3
Using Lower-Level Websockets with the Streaming API - Deepgram's Docs, accessed November 10, 2025, https://developers.deepgram.com/docs/lower-level-websockets
How to stream audio data from Android to WebSocket server? - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/68747516/how-to-stream-audio-data-from-android-to-websocket-server
Send live audio stream from client to server using WebSocket and OkHttp client - Reddit, accessed November 10, 2025, https://www.reddit.com/r/androiddev/comments/tay128/send_live_audio_stream_from_client_to_server/
WebSockets in Android. Setup plain websocket using OkHttp… | by Sudendra - Medium, accessed November 10, 2025, https://medium.com/@sudhukl/websockets-in-android-e6ff2f3e1ebb
Pricing - Cartesia, accessed November 10, 2025, https://cartesia.ai/pricing
Real-time TTS API with AI laughter and emotion | Cartesia Sonic-3, accessed November 10, 2025, https://cartesia.ai/sonic
Best TTS APIs in 2025: Top 12 Text-to-Speech services for developers - Speechmatics, accessed November 10, 2025, https://www.speechmatics.com/company/articles-and-news/best-tts-apis-in-2025-top-12-text-to-speech-services-for-developers
How to Choose STT and TTS for Voice Agents: Latency, Accuracy, Cost - Softcery, accessed November 10, 2025, https://softcery.com/lab/how-to-choose-stt-tts-for-ai-voice-agents-in-2025-a-comprehensive-guide
Cartesia AI: The Ultimate Guide to Real-Time Voice Intelligence - Skywork.ai, accessed November 10, 2025, https://skywork.ai/skypage/en/Cartesia-AI:-The-Ultimate-Guide-to-Real-Time-Voice-Intelligence/1976180708227084288
Ink | Cartesia, accessed November 10, 2025, https://cartesia.ai/ink
LiveKit Inference Pricing, accessed November 10, 2025, https://livekit.io/pricing/inference
AssemblyAI vs Deepgram: Which Speech-to-Text API Handles Production Scale?, accessed November 10, 2025, https://deepgram.com/learn/assemblyai-vs-deepgram
Deepgram vs. Cartesia, accessed November 10, 2025, https://deepgram.com/compare/cartesia-vs-deepgram
Top APIs and models for real-time speech recognition and transcription in 2025, accessed November 10, 2025, https://www.assemblyai.com/blog/best-api-models-for-real-time-speech-recognition-and-transcription
Deepgram AI vs AssemblyAI | Speech-to-Text, accessed November 10, 2025, https://www.assemblyai.com/deepgram-vs-assemblyai
I benchmarked 12+ speech-to-text APIs under various real-world conditions - Reddit, accessed November 10, 2025, https://www.reddit.com/r/speechtech/comments/1kd9abp/i_benchmarked_12_speechtotext_apis_under_various/
5 Deepgram alternatives in 2025 - AssemblyAI, accessed November 10, 2025, https://www.assemblyai.com/blog/deepgram-alternatives
AWS Transcribe vs Deepgram vs Whisper: Guide to Speech Recognition APIs - CMARIX, accessed November 10, 2025, https://www.cmarix.com/blog/aws-transcribe-vs-deepgram-vs-whisper/
Best Speech-to-Text APIs in 2025 - Deepgram, accessed November 10, 2025, https://deepgram.com/learn/best-speech-to-text-apis
Benchmark Report: OpenAI Whisper vs. Deepgram, accessed November 10, 2025, https://offers.deepgram.com/hubfs/Whitepaper%20Deepgram%20vs%20Whisper%20Benchmark.pdf
Foreground Service killed by android OS - Stack Overflow, accessed November 10, 2025, https://stackoverflow.com/questions/74217791/foreground-service-killed-by-android-os
Android - start microphone from a foreground service whilst the app is not in focus, accessed November 10, 2025, https://stackoverflow.com/questions/77671706/android-start-microphone-from-a-foreground-service-whilst-the-app-is-not-in-fo
How to Prioritize Features for Your Minimum Viable Product (MVP) - Net Solutions, accessed November 10, 2025, https://www.netsolutions.com/hub/minimum-viable-product/prioritize-features/
Product Requirement Document for a MVP : r/ProductManagement - Reddit, accessed November 10, 2025, https://www.reddit.com/r/ProductManagement/comments/ud0n61/product_requirement_document_for_a_mvp/
vvvt/android-speaker-identification: :iphone - GitHub, accessed November 10, 2025, https://github.com/vvvt/android-speaker-identification
