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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frontieraudio.heartbeat.audio.SpeechSegmentAssembler
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfig
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfigStore
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationResult
import com.frontieraudio.heartbeat.speaker.SpeakerVerifier
import com.frontieraudio.heartbeat.speaker.VerifiedSpeechSegment
import com.frontieraudio.heartbeat.speaker.VoiceProfile
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import java.util.Locale

class HeartbeatService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
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
        preRollDurationMs = PRE_ROLL_DURATION_MS,
        minSegmentDurationMs = MIN_SEGMENT_DURATION_MS
    ) { segment ->
        Log.i(
            TAG,
            "Stage1 segment ready id=${segment.id} duration=${segment.durationMillis}ms samples=${segment.durationSamples}"
        )
        Trace.beginSection("Heartbeat.Stage1SegmentReady")
        try {
            speechSegmentsInternal.emit(segment)
        } finally {
            Trace.endSection()
        }
    }
    private val voiceProfileStore by lazy { VoiceProfileStore(applicationContext) }
    private val speakerVerifierLazy = lazy { SpeakerVerifier(applicationContext) }
    private val speakerVerifier: SpeakerVerifier
        get() = speakerVerifierLazy.value
    private val configStore by lazy { SpeakerVerificationConfigStore(applicationContext) }
    private val verifiedSegmentsInternal = MutableSharedFlow<VerifiedSpeechSegment>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Enable atrace for this app so Trace.beginSection() calls are captured
        enableAppTracing()
        startVerificationPipeline()
    }

    private fun enableAppTracing() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use reflection to call Trace.setAppTracingAllowed(true)
                val method = Trace::class.java.getMethod("setAppTracingAllowed", Boolean::class.javaPrimitiveType)
                method.invoke(null, true)
                Log.i(TAG, "App tracing enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable app tracing (this is normal on some devices)", e)
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
        if (speakerVerifierLazy.isInitialized()) {
            speakerVerifier.close()
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun speechSegments(): SharedFlow<SpeechSegment> = speechSegmentsInternal.asSharedFlow()

    fun verifiedSegments(): SharedFlow<VerifiedSpeechSegment> = verifiedSegmentsInternal.asSharedFlow()

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
            VerificationState(isVerified = false, similarity = null, validUntilElapsedRealtimeMs = null)
        }
    }

    private fun startVerificationPipeline() {
        profileJob?.cancel()
        configJob?.cancel()
        verificationJob?.cancel()

        profileJob = serviceScope.launch {
            voiceProfileStore.voiceProfileFlow.collect { profile ->
                currentProfile = profile
                if (profile == null) {
                    activeVerification = null
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
                handleSegmentForVerification(segment)
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
    }

    private suspend fun handleSegmentForVerification(segment: SpeechSegment) {
        val profile = currentProfile ?: run {
            Log.d(TAG, "Stage2: skipping segment ${segment.id} - no enrolled profile")
            return
        }
        val config = currentConfig
        val nowMs = SystemClock.elapsedRealtime()
        pruneActiveState(nowMs)

        if (nowMs - lastRejectAtMs < config.negativeCooldownMillis) {
            Log.d(TAG, "Stage2: cooling down; skipping segment ${segment.id}")
            return
        }

        when (val result = speakerVerifier.verify(segment, profile, config.matchThreshold)) {
            is SpeakerVerificationResult.Match -> {
                val validUntil = nowMs + config.positiveRetentionMillis
                activeVerification = ActiveVerificationState(
                    similarity = result.similarity,
                    scores = result.scores,
                    validUntilMs = validUntil
                )
                emitVerifiedSegment(segment, result.similarity, result.scores)
                Log.i(
                    TAG,
                    "Stage2 accepted segment id=${segment.id} similarity=${String.format(Locale.US, "%.3f", result.similarity)}"
                )
            }
            is SpeakerVerificationResult.NoMatch -> {
                activeVerification = null
                lastRejectAtMs = nowMs
                Log.i(
                    TAG,
                    "Stage2 rejected segment id=${segment.id} similarity=${String.format(Locale.US, "%.3f", result.similarity)}"
                )
            }
            is SpeakerVerificationResult.NotEnrolled -> {
                activeVerification = null
                lastRejectAtMs = nowMs
                Log.w(TAG, "Stage2 reported not enrolled; dropping segment ${segment.id}")
            }
            is SpeakerVerificationResult.Error -> {
                activeVerification = null
                lastRejectAtMs = nowMs
                Log.e(TAG, "Stage2 error for segment ${segment.id}", result.throwable)
            }
        }
    }

    private suspend fun emitVerifiedSegment(segment: SpeechSegment, similarity: Float, scores: FloatArray) {
        verifiedSegmentsInternal.emit(
            VerifiedSpeechSegment(
                segment = segment,
                similarity = similarity,
                scores = scores,
                verifiedAtMillis = System.currentTimeMillis()
            )
        )
    }

    private fun pruneActiveState(nowMs: Long) {
        val state = activeVerification
        if (state != null && nowMs > state.validUntilMs) {
            activeVerification = null
        }
    }

    private fun startAudioPipeline() {
        if (processingJob?.isActive == true) {
            return
        }

        processingJob = serviceScope.launch {
            var backoffMs = RESTART_BACKOFF_INITIAL_MS
            while (isActive) {
                try {
                    prepareAudioComponents()
                    processAudioStream()
                    return@launch
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.e(TAG, "Audio processing failed", t)
                    if (!isActive) {
                        throw t
                    }
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
        if (audioRecord != null && vad != null) {
            return
        }

        val sampleRate = SampleRate.SAMPLE_RATE_16K
        val frameSize = FrameSize.FRAME_SIZE_512

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate.value,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Invalid minimum buffer size for AudioRecord")
        }

        val bufferSizeInBytes = max(
            minBufferSize,
            frameSize.value * BYTES_PER_SAMPLE
        )

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate.value)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        val vadInstance = Vad.builder()
            .setContext(applicationContext)
            .setSampleRate(sampleRate)
            .setFrameSize(frameSize)
            .setMode(Mode.NORMAL)
            .setSpeechDurationMs(SPEECH_DURATION_MS)
            .setSilenceDurationMs(SILENCE_DURATION_MS)
            .build()

        audioRecord = record
        vad = vadInstance
    }

    private suspend fun processAudioStream() {
        val record = audioRecord ?: return
        val detector = vad ?: return

        val frameSizeSamples = detector.frameSize.value
        val frameBuffer = ShortArray(frameSizeSamples)
        var frameOffset = 0

        record.startRecording()

        while (serviceScope.isActive) {
            val read = record.read(
                frameBuffer,
                frameOffset,
                frameSizeSamples - frameOffset,
                AudioRecord.READ_BLOCKING
            )
            if (read < 0) {
                throw IllegalStateException(
                    "AudioRecord.read() failed with code $read (${audioReadErrorName(read)})"
                )
            }

            if (read == 0) {
                continue
            }

            frameOffset += read

            if (frameOffset >= frameSizeSamples) {
                val frameTimestampNs = SystemClock.elapsedRealtimeNanos()
                val frameCopy = frameBuffer.copyOf()
                val isSpeech = detector.isSpeech(frameBuffer)
                handleSpeechResult(frameCopy, isSpeech, frameTimestampNs)
                frameOffset = 0
            }
        }
    }

    private suspend fun handleSpeechResult(
        frame: ShortArray,
        isSpeech: Boolean,
        frameTimestampNs: Long
    ) {
        segmentAssembler.onFrame(frame, isSpeech, frameTimestampNs)

        if (isSpeech && !speechDetected) {
            Trace.beginSection("Heartbeat.VAD_SpeechDetected")
            speechDetected = true
            Log.i(TAG, "VAD: Speech Detected")
            Trace.endSection()
        } else if (!isSpeech && speechDetected) {
            Trace.beginSection("Heartbeat.VAD_SpeechEnded")
            Trace.endSection()
            speechDetected = false
        }
    }

    private fun teardownAudioComponents() {
        audioRecord?.apply {
            try {
                stop()
            } catch (ignored: IllegalStateException) {
                // Already stopped.
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

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
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

    companion object {
        private const val TAG = "Heartbeat"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "heartbeat_monitor"
        private const val BYTES_PER_SAMPLE = 2
        private const val SPEECH_DURATION_MS = 80
        private const val SILENCE_DURATION_MS = 300
        private const val PRE_ROLL_DURATION_MS = 240
        private const val MIN_SEGMENT_DURATION_MS = 0
        private const val RESTART_BACKOFF_INITIAL_MS = 1_000L
        private const val RESTART_BACKOFF_MAX_MS = 8_000L

        fun start(context: Context) {
            val intent = Intent(context, HeartbeatService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HeartbeatService::class.java)
            context.stopService(intent)
        }
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

