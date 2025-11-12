package com.frontieraudio.heartbeat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Trace
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frontieraudio.heartbeat.audio.AudioEffectUtils
import com.frontieraudio.heartbeat.audio.SpeechSegmentAssembler
import com.frontieraudio.heartbeat.diagnostics.DiagnosticsEvent
import com.frontieraudio.heartbeat.diagnostics.DiagnosticsEvent.Source
import com.frontieraudio.heartbeat.diagnostics.SegmentDiagnosticsAnalyzer
import com.frontieraudio.heartbeat.location.LocationData
import com.frontieraudio.heartbeat.location.LocationManager
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfig
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfigStore
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationResult
import com.frontieraudio.heartbeat.speaker.SpeakerVerifier
import com.frontieraudio.heartbeat.speaker.VerifiedSpeechSegment
import com.frontieraudio.heartbeat.speaker.VoiceProfile
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
import com.frontieraudio.heartbeat.debug.AppLogger
import com.frontieraudio.heartbeat.transcription.CartesiaWebSocketClient
import com.frontieraudio.heartbeat.transcription.TranscriptionConfig
import com.frontieraudio.heartbeat.transcription.TranscriptionConfigStore
import com.frontieraudio.heartbeat.transcription.TranscriptionResult
import com.frontieraudio.heartbeat.metrics.MetricsCollector
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.max

class HeartbeatService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    private var processingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var vad: VadSilero? = null
    private var speechDetected = false
    private val speechSegmentsInternal = MutableSharedFlow<SpeechSegment>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val segmentAssembler = SpeechSegmentAssembler(
        sampleRateHz = SampleRate.SAMPLE_RATE_16K.value,
        frameSizeSamples = FrameSize.FRAME_SIZE_512.value,
        preRollDurationMs = PRE_ROLL_DURATION_MS
    ) { segment ->
        // Start metrics tracking for this segment
        metricsCollector.startTracking(segment.id)
        metricsCollector.markVadComplete(segment.id, segment.durationMillis, segment.samples.size)

        AppLogger.i(
            TAG,
            "Stage1 segment ready id=${segment.id} duration=${segment.durationMillis}ms samples=${segment.durationSamples}"
        )
        Trace.beginSection("Heartbeat.Stage1SegmentReady")
        try {
            speechSegmentsInternal.tryEmit(segment)
        } finally {
            Trace.endSection()
        }
    }
    private val voiceProfileStore by lazy { (application as HeartbeatApplication).voiceProfileStore }
    private val speakerVerifierLazy = lazy { SpeakerVerifier(applicationContext) }
    private val speakerVerifier: SpeakerVerifier
        get() = speakerVerifierLazy.value
    private val configStore by lazy { (application as HeartbeatApplication).configStore }
    private val transcriptionConfigStore by lazy { (application as HeartbeatApplication).transcriptionConfigStore }
    private val verifiedSegmentsInternal = MutableSharedFlow<VerifiedSpeechSegment>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val diagnosticsInternal = MutableSharedFlow<DiagnosticsEvent>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Stage 3: Cloud Transcription components
    private val transcriptionResultsInternal = MutableSharedFlow<TranscriptionResult>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val locationManager by lazy { LocationManager(applicationContext, serviceScope) }
    private var cartesiaClient: CartesiaWebSocketClient? = null
    private var currentLocation: LocationData? = null
    private var transcriptionJob: Job? = null

    // Metrics collection
    private val metricsCollector = MetricsCollector()

    // State machine for streaming transcription
    private enum class TranscriptionState { IDLE, VERIFYING, STREAMING }

    private var transcriptionState = TranscriptionState.IDLE
    private val chunkBuffer = mutableListOf<SpeechSegment>()
    private var pendingVerificationChunk: SpeechSegment? = null
    private var currentStreamingSegmentId: Long = -1

    private val notVerifiedState =
        VerificationState(isVerified = false, similarity = null, validUntilElapsedRealtimeMs = null)
    private val verificationStateInternal = MutableStateFlow(notVerifiedState)
    private var verificationJob: Job? = null
    private var profileJob: Job? = null
    private var configJob: Job? = null

    @Volatile
    private var currentProfile: VoiceProfile? = null

    @Volatile
    private var currentConfig: SpeakerVerificationConfig = SpeakerVerificationConfig()

    @Volatile
    private var activeVerification: ActiveVerificationState? = null

    @Volatile
    private var lastRejectAtMs: Long = 0L

    @Volatile
    private var lastAudioEffectsWarning: List<String>? = null

    @Volatile
    private var bypassVerification: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        createNotificationChannel()
        enableAppTracing()
        startVerificationPipeline()
        startTranscriptionPipeline()
        startLocationTracking()
    }

    private fun enableAppTracing() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val method = Trace::class.java.getMethod("setAppTracingAllowed", Boolean::class.javaPrimitiveType)
                method.invoke(null, true)
                AppLogger.i(TAG, "App tracing enabled")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not enable app tracing (this is normal on some devices): ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startAudioPipeline()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPipeline()
        stopVerificationPipeline()
        stopTranscriptionPipeline()
        stopLocationTracking()
        if (speakerVerifierLazy.isInitialized()) {
            speakerVerifier.close()
        }
        serviceScope.cancel()
        instanceRef?.clear()
        instanceRef = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun speechSegments(): SharedFlow<SpeechSegment> = speechSegmentsInternal.asSharedFlow()
    fun verifiedSegments(): SharedFlow<VerifiedSpeechSegment> = verifiedSegmentsInternal.asSharedFlow()
    fun diagnosticsFlow(): SharedFlow<DiagnosticsEvent> = diagnosticsInternal.asSharedFlow()
    fun verificationStateFlow(): StateFlow<VerificationState> = verificationStateInternal.asStateFlow()
    fun transcriptionResults(): SharedFlow<TranscriptionResult> = transcriptionResultsInternal.asSharedFlow()
    fun getCurrentLocation(): LocationData? = currentLocation
    fun getMetricsCollector(): MetricsCollector = metricsCollector

    fun verificationState(): VerificationState {
        val now = SystemClock.elapsedRealtime()
        pruneActiveState(now)
        val state = activeVerification
        return if (state != null) {
            VerificationState(
                isVerified = true,
                similarity = state.similarity,
                validUntilElapsedRealtimeMs = state.validUntilMs
            )
        } else {
            notVerifiedState
        }
    }

    private fun startVerificationPipeline() {
        AppLogger.d(TAG, "Starting verification pipeline")
        profileJob?.cancel()
        configJob?.cancel()
        verificationJob?.cancel()
        verificationStateInternal.value = notVerifiedState

        profileJob = serviceScope.launch {
            voiceProfileStore.voiceProfileFlow.collect { profile ->
                currentProfile = profile
                AppLogger.d(TAG, "Profile updated: ${if (profile != null) "loaded" else "null"}")
                if (profile == null) {
                    activeVerification = null
                    verificationStateInternal.value = notVerifiedState
                }
            }
        }

        configJob = serviceScope.launch {
            configStore.config.collect { config ->
                currentConfig = config
                pruneActiveState(SystemClock.elapsedRealtime())
            }
        }

        verificationJob = serviceScope.launch {
            speechSegmentsInternal.collect { segment ->
                handleAudioChunk(segment)
            }
        }
    }

    private fun stopVerificationPipeline() {
        verificationJob?.cancel()
        verificationJob = null
        profileJob?.cancel()
        profileJob = null
        configJob?.cancel()
        configJob = null
        activeVerification = null
        verificationStateInternal.value = notVerifiedState
    }

    private suspend fun handleAudioChunk(chunk: SpeechSegment) {
        AppLogger.d(TAG, "handleAudioChunk: chunk ${chunk.id}, current state=$transcriptionState")
        when (transcriptionState) {
            TranscriptionState.IDLE -> {
                AppLogger.i(TAG, "State: IDLE → VERIFYING. Received chunk ${chunk.id}. Starting verification.")
                transcriptionState = TranscriptionState.VERIFYING
                pendingVerificationChunk = chunk
                verifyChunk(chunk)
            }

            TranscriptionState.VERIFYING -> {
                AppLogger.d(TAG, "State: VERIFYING. Buffering chunk ${chunk.id}. Buffer size: ${chunkBuffer.size + 1}")
                chunkBuffer.add(chunk)
            }

            TranscriptionState.STREAMING -> {
                AppLogger.d(TAG, "State: STREAMING. Streaming chunk ${chunk.id} to Cartesia.")
                cartesiaClient?.streamAudioChunk(chunk.samples)
            }
        }
    }

    private suspend fun verifyChunk(chunk: SpeechSegment) {
        AppLogger.d(TAG, "Stage2: processing chunk ${chunk.id}")

        // Bypass verification if test mode is enabled
        if (bypassVerification) {
            AppLogger.i(
                TAG,
                "⚠️ BYPASS MODE: Skipping verification for chunk ${chunk.id}, going directly to transcription"
            )
            onVerificationSuccess(
                SpeakerVerificationResult.Match(
                    similarity = 1.0f,
                    scores = floatArrayOf(1.0f)
                ),
                1.0f,
                chunk
            )
            return
        }

        val profile = currentProfile ?: run {
            AppLogger.d(TAG, "Stage2: skipping chunk ${chunk.id} - no enrolled profile")
            resetTranscriptionState()
            return
        }
        val config = currentConfig
        val nowMs = SystemClock.elapsedRealtime()
        pruneActiveState(nowMs)

        // Track Stage 2 start
        metricsCollector.markStage2Start(chunk.id)

        if (nowMs - lastRejectAtMs < config.negativeCooldownMillis) {
            AppLogger.d(TAG, "Stage2: cooling down; skipping chunk ${chunk.id}")
            resetTranscriptionState() // Reset state machine if in cooldown
            return
        }

        // OPTIMIZATION 5: Length-based threshold scaling
        val durationSeconds = chunk.samples.size / 16000.0
        val baseThreshold = config.matchThreshold
        val effectiveThreshold = when {
            durationSeconds < 0.5 -> baseThreshold * 0.6f  // Very short: 60% of threshold
            durationSeconds < 1.0 -> baseThreshold * 0.8f  // Short: 80% of threshold
            else -> baseThreshold                          // Normal: 100% of threshold
        }

        AppLogger.d(
            TAG,
            "Stage2: duration=${
                String.format(
                    Locale.US,
                    "%.2f",
                    durationSeconds
                )
            }s, base threshold=$baseThreshold, effective threshold=$effectiveThreshold"
        )

        val metrics = SegmentDiagnosticsAnalyzer.analyze(chunk)
        logSegmentDiagnostics(chunk, metrics)
        var similarity: Float? = null
        var matched: Boolean? = null

        when (val result = speakerVerifier.verify(chunk, profile, effectiveThreshold)) {
            is SpeakerVerificationResult.Match -> {
                similarity = result.similarity
                matched = true
                AppLogger.i(
                    TAG,
                    "Stage2 accepted chunk id=${chunk.id} similarity=${
                        String.format(
                            Locale.US,
                            "%.3f",
                            result.similarity
                        )
                    }"
                )
                onVerificationSuccess(result, effectiveThreshold)
            }

            is SpeakerVerificationResult.NoMatch -> {
                similarity = result.similarity
                matched = false
                lastRejectAtMs = nowMs
                AppLogger.i(
                    TAG,
                    "Stage2 rejected chunk id=${chunk.id} similarity=${
                        String.format(
                            Locale.US,
                            "%.3f",
                            result.similarity
                        )
                    }"
                )
                onVerificationFailure()
            }

            is SpeakerVerificationResult.NotEnrolled, is SpeakerVerificationResult.Error -> {
                lastRejectAtMs = nowMs
                AppLogger.e(
                    TAG,
                    "Stage2 error or not enrolled for chunk ${chunk.id}",
                    (result as? SpeakerVerificationResult.Error)?.throwable
                )
                onVerificationFailure()
            }
        }
        // Diagnostics emission can be added here if needed
    }

    private fun onVerificationSuccess(
        result: SpeakerVerificationResult.Match,
        threshold: Float,
        chunk: SpeechSegment? = null
    ) {
        serviceScope.launch {
            AppLogger.i(TAG, "✅ VERIFICATION SUCCESS - Starting Stage 3 transcription")

            // Create verified segment
            val verifiedSegment = VerifiedSpeechSegment(
                segment = chunk ?: pendingVerificationChunk!!,
                similarity = result.similarity,
                scores = result.scores,
                verifiedAtMillis = System.currentTimeMillis()
            )

            // Track Stage 2 completion
            val segmentId = verifiedSegment.segment.id
            metricsCollector.markStage2Complete(
                segmentId,
                result.similarity,
                threshold,
                true,
                bypassVerification
            )

            // Update verification state to show green card
            val nowMs = SystemClock.elapsedRealtime()
            val retentionMs = currentConfig?.positiveRetentionMillis ?: 5000L
            activeVerification = ActiveVerificationState(
                similarity = verifiedSegment.similarity,
                scores = verifiedSegment.scores,
                validUntilMs = nowMs + retentionMs
            )
            verificationStateInternal.value = VerificationState(
                isVerified = true,
                similarity = verifiedSegment.similarity,
                validUntilElapsedRealtimeMs = nowMs + retentionMs
            )
            AppLogger.i(
                TAG,
                "🟢 Green card activated for ${retentionMs}ms (similarity=${
                    String.format(
                        "%.3f",
                        verifiedSegment.similarity
                    )
                })"
            )

            // Transition to STREAMING state
            transcriptionState = TranscriptionState.STREAMING
            AppLogger.i(TAG, "State: VERIFYING → STREAMING")

            // Start the WebSocket session and wait for it to connect
            val client = cartesiaClient
            if (client != null) {
                val isConnected = client.isConnected()
                AppLogger.i(TAG, "Cartesia client status: connected=$isConnected")
                if (!isConnected) {
                    AppLogger.i(TAG, "WebSocket not connected, waiting for connection...")
                }
                AppLogger.i(TAG, "Starting Cartesia WebSocket transcription session")
                client.startTranscriptionSession() // This now waits for connection

                if (!client.isConnected()) {
                    AppLogger.e(TAG, "❌ WebSocket failed to connect - cannot stream audio")
                    resetTranscriptionState()
                    return@launch
                }
            } else {
                AppLogger.e(TAG, "❌ Cannot start transcription - Cartesia client is null!")
                resetTranscriptionState()
                return@launch
            }

            // Mark Stage 3 start
            metricsCollector.markStage3Start(segmentId)

            // Store current segment ID for metrics tracking
            currentStreamingSegmentId = segmentId

            // Emit verified segment
            verifiedSegmentsInternal.tryEmit(verifiedSegment)

            // Stream the chunk that was just verified
            pendingVerificationChunk?.let {
                AppLogger.i(TAG, "Streaming pending verification chunk ${it.id} (${it.samples.size} samples)")
                client.streamAudioChunk(it.samples)
            }

            // Stream any buffered chunks
            if (chunkBuffer.isNotEmpty()) {
                AppLogger.i(TAG, "Streaming ${chunkBuffer.size} buffered chunks")
                chunkBuffer.forEachIndexed { index, bufferedChunk ->
                    AppLogger.d(
                        TAG,
                        "Streaming buffered chunk ${index + 1}/${chunkBuffer.size}: id=${bufferedChunk.id}"
                    )
                    client.streamAudioChunk(bufferedChunk.samples)
                }
            } else {
                AppLogger.d(TAG, "No buffered chunks to stream")
            }

            // Clear state
            pendingVerificationChunk = null
            chunkBuffer.clear()
            AppLogger.i(TAG, "Stage 3: Now actively streaming audio to Cartesia (segment $currentStreamingSegmentId)")
        }
    }

    private fun onVerificationFailure() {
        AppLogger.w(TAG, "❌ Verification failed. Discarding ${chunkBuffer.size} buffered chunks and resetting state.")

        // Cancel metrics tracking for failed verification
        pendingVerificationChunk?.let { chunk ->
            metricsCollector.cancelTracking(chunk.id)
        }

        // Also cancel current streaming segment if any
        if (currentStreamingSegmentId != -1L) {
            metricsCollector.cancelTracking(currentStreamingSegmentId)
            currentStreamingSegmentId = -1
        }

        resetTranscriptionState()
    }

    private fun resetTranscriptionState() {
        AppLogger.d(TAG, "Resetting transcription state: $transcriptionState → IDLE")
        transcriptionState = TranscriptionState.IDLE
        chunkBuffer.clear()
        pendingVerificationChunk = null
        currentStreamingSegmentId = -1
    }

    private fun logSegmentDiagnostics(segment: SpeechSegment, metrics: SegmentDiagnosticsAnalyzer.Metrics) {
        val warnings = if (metrics.warnings.isEmpty()) "none" else metrics.warnings.joinToString()
        AppLogger.i(
            TAG,
            String.format(
                Locale.US,
                "Stage2 diagnostics id=%d rms=%.1f dBFS peak=%.1f dBFS clipping=%.1f%% speech=%.0f%% warnings=%s",
                segment.id,
                metrics.rmsDbfs,
                metrics.peakDbfs,
                metrics.clippingRatio * 100f,
                metrics.speechRatio * 100f,
                warnings
            )
        )
    }

    private fun detectAudioEffects(record: AudioRecord) {
        val activeEffects = AudioEffectUtils.detectEnabledEffects(record)
        if (activeEffects.isEmpty()) {
            if (!lastAudioEffectsWarning.isNullOrEmpty()) {
                lastAudioEffectsWarning = null
                diagnosticsInternal.tryEmit(DiagnosticsEvent.AudioEffectsWarning(Source.VERIFICATION, emptyList()))
            }
            return
        }
        if (lastAudioEffectsWarning == activeEffects) return
        lastAudioEffectsWarning = activeEffects.toList()
        AppLogger.w(TAG, "Detected device audio effects enabled: ${activeEffects.joinToString()}")
        diagnosticsInternal.tryEmit(DiagnosticsEvent.AudioEffectsWarning(Source.VERIFICATION, activeEffects))
    }

    private fun pruneActiveState(nowMs: Long) {
        val state = activeVerification
        if (state != null && nowMs > state.validUntilMs) {
            activeVerification = null
            verificationStateInternal.value = notVerifiedState
        }
    }

    private fun startAudioPipeline() {
        if (processingJob?.isActive == true) return
        processingJob = serviceScope.launch {
            var backoffMs = RESTART_BACKOFF_INITIAL_MS
            while (isActive) {
                try {
                    prepareAudioComponents()
                    processAudioStream()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Audio processing failed", t)
                    if (!isActive) throw t
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                } finally {
                    teardownAudioComponents()
                }
            }
        }
    }

    private fun stopAudioPipeline() {
        processingJob?.cancel()
        processingJob = null
        teardownAudioComponents()
    }

    @SuppressLint("MissingPermission")
    private suspend fun prepareAudioComponents() {
        if (audioRecord != null && vad != null) return

        val sampleRate = SampleRate.SAMPLE_RATE_16K
        val frameSize = FrameSize.FRAME_SIZE_512
        val minBufferSize =
            AudioRecord.getMinBufferSize(sampleRate.value, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Invalid minimum buffer size for AudioRecord")
        }
        val bufferSizeInBytes = max(minBufferSize, frameSize.value * BYTES_PER_SAMPLE)
        val audioFormat =
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate.value)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat).setBufferSizeInBytes(bufferSizeInBytes).build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        detectAudioEffects(record)
        val vadInstance = Vad.builder().setContext(applicationContext).setSampleRate(sampleRate).setFrameSize(frameSize)
            .setMode(Mode.NORMAL).setSpeechDurationMs(SPEECH_DURATION_MS).setSilenceDurationMs(SILENCE_DURATION_MS)
            .build()
        audioRecord = record
        vad = vadInstance
    }

    private suspend fun processAudioStream() {
        val record = audioRecord ?: return
        val detector = vad ?: return
        val frameBuffer = ShortArray(detector.frameSize.value)
        record.startRecording()
        while (serviceScope.isActive) {
            val read = record.read(frameBuffer, 0, frameBuffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) throw IllegalStateException(
                "AudioRecord.read() failed with code $read (${
                    audioReadErrorName(
                        read
                    )
                })"
            )
            if (read == 0) continue
            val frameTimestampNs = SystemClock.elapsedRealtimeNanos()
            val frameCopy = frameBuffer.copyOf()
            val isSpeech = detector.isSpeech(frameCopy)
            handleSpeechResult(frameCopy, isSpeech, frameTimestampNs)
        }
    }

    private suspend fun handleSpeechResult(frame: ShortArray, isSpeech: Boolean, frameTimestampNs: Long) {
        segmentAssembler.onFrame(frame, isSpeech, frameTimestampNs)

        if (isSpeech && !speechDetected) {
            Trace.beginSection("Heartbeat.VAD_SpeechDetected")
            speechDetected = true
            AppLogger.i(TAG, "VAD: Speech Detected")
            Trace.endSection()
        } else if (!isSpeech && speechDetected) {
            Trace.beginSection("Heartbeat.VAD_SpeechEnded")
            AppLogger.i(TAG, "🔇 VAD: Speech Ended")
            if (transcriptionState == TranscriptionState.STREAMING) {
                AppLogger.i(TAG, "Speech ended while STREAMING - finalizing transcription session")
                cartesiaClient?.endTranscriptionSession()
            } else {
                AppLogger.d(TAG, "Speech ended in state $transcriptionState (not streaming)")
            }
            resetTranscriptionState()
            Trace.endSection()
            speechDetected = false
        }
    }

    private fun teardownAudioComponents() {
        audioRecord?.apply {
            try {
                stop()
            } catch (ignored: IllegalStateException) {
            }
            release()
        }
        audioRecord = null
        vad?.close()
        vad = null
        speechDetected = false
        segmentAssembler.reset()
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntentFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pendingIntent).setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    private fun startTranscriptionPipeline() {
        AppLogger.d(TAG, "Starting Stage 3 transcription pipeline")
        var configJob: Job? = serviceScope.launch {
            transcriptionConfigStore.configFlow.collect { config ->
                if (config.cartesiaApiKey.isNotBlank()) {
                    AppLogger.i(TAG, "Transcription config updated with API key.")
                    AppLogger.i(TAG, "Cartesia URL: ${config.cartesiaUrl}")
                    AppLogger.i(
                        TAG,
                        "API Key configured: ${if (config.cartesiaApiKey.length > 8) "${config.cartesiaApiKey.take(8)}..." else "present"}"
                    )
                    cartesiaClient?.disconnect()
                    cartesiaClient = CartesiaWebSocketClient(config, serviceScope)
                    AppLogger.i(TAG, "Connecting to Cartesia WebSocket...")
                    cartesiaClient?.connect()
                    // Give it a moment to connect
                    delay(500)
                    val connected = cartesiaClient?.isConnected() ?: false
                    AppLogger.i(TAG, "Cartesia WebSocket connection status: $connected")
                } else {
                    AppLogger.w(TAG, "Cartesia API key not configured")
                    cartesiaClient?.disconnect()
                    cartesiaClient = null
                }
            }
        }
        transcriptionJob = serviceScope.launch {
            while (isActive) {
                val client = cartesiaClient
                if (client != null) {
                    try {
                        client.transcriptionResults.collect { result ->
                            handleTranscriptionResult(result)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error collecting transcription results", e)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopTranscriptionPipeline() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        cartesiaClient?.disconnect()
        cartesiaClient = null
        AppLogger.d(TAG, "Stage 3 transcription pipeline stopped")
    }

    private fun startLocationTracking() {
        if (locationManager.hasLocationPermissions()) {
            locationManager.startLocationUpdates()
            serviceScope.launch {
                locationManager.locationUpdates.collect { location ->
                    currentLocation = location
                    AppLogger.d(TAG, "Location updated: lat=${location.latitude}, lon=${location.longitude}")
                }
            }
        } else {
            AppLogger.w(TAG, "Location permissions not granted, location tracking disabled")
        }
    }

    private fun stopLocationTracking() {
        locationManager.cleanup()
        currentLocation = null
        AppLogger.d(TAG, "Location tracking stopped")
    }

    private suspend fun handleTranscriptionResult(result: TranscriptionResult) {
        val truncated = if (result.transcript.length > 50) {
            "${result.transcript.take(50)}..."
        } else {
            result.transcript
        }
        AppLogger.i(
            TAG,
            "📝 Stage 3 transcription result: '${truncated}' [${if (result.isFinal) "FINAL" else "PARTIAL"}] (${result.transcript.length} chars)"
        )

        // Track transcription metrics using current streaming segment ID
        if (currentStreamingSegmentId != -1L) {
            if (!result.isFinal && result.transcript.isNotBlank()) {
                // Mark first partial
                metricsCollector.markFirstPartial(currentStreamingSegmentId)
            } else if (result.isFinal && result.transcript.isNotBlank()) {
                // Mark final transcript
                metricsCollector.markFinalTranscript(currentStreamingSegmentId, result.transcript)
                // Reset after final transcript
                currentStreamingSegmentId = -1
            }
        } else {
            AppLogger.w(TAG, "Received transcription but no segment ID tracked for metrics")
        }

        transcriptionResultsInternal.emit(result)
        AppLogger.d(TAG, "Transcription result emitted to MainActivity")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun setBypassVerification(bypass: Boolean) {
        bypassVerification = bypass
        AppLogger.i(TAG, "Bypass verification set to: $bypass")
    }

    companion object {
        private const val TAG = "Heartbeat"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "heartbeat_monitor"
        private const val BYTES_PER_SAMPLE = 2
        private const val SPEECH_DURATION_MS = 80
        private const val SILENCE_DURATION_MS = 300
        private const val PRE_ROLL_DURATION_MS = 240
        private const val MIN_SEGMENT_DURATION_MS = 500 // Lowered for streaming
        private const val RESTART_BACKOFF_INITIAL_MS = 1_000L
        private const val RESTART_BACKOFF_MAX_MS = 8_000L

        @Volatile
        private var instanceRef: WeakReference<HeartbeatService>? = null

        fun start(context: Context) {
            val intent = Intent(context, HeartbeatService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HeartbeatService::class.java)
            context.stopService(intent)
        }

        fun getInstance(): HeartbeatService? = instanceRef?.get()
    }

    private fun audioReadErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> "UNKNOWN"
    }

    data class VerificationState(
        val isVerified: Boolean,
        val similarity: Float?,
        val validUntilElapsedRealtimeMs: Long?
    )

    private data class ActiveVerificationState(
        val similarity: Float,
        val scores: FloatArray,
        val validUntilMs: Long
    )
}
