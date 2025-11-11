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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
import kotlin.math.max

class HeartbeatService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private var processingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var vad: VadSilero? = null
    private var speechDetected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startAudioPipeline()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPipeline()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                val isSpeech = detector.isSpeech(frameBuffer)
                handleSpeechResult(isSpeech)
                frameOffset = 0
            }
        }
    }

    private fun handleSpeechResult(isSpeech: Boolean) {
        if (isSpeech) {
            if (!speechDetected) {
                speechDetected = true
                Log.i(TAG, "VAD: Speech Detected")
            }
        } else {
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
}


