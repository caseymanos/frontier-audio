package com.frontieraudio.heartbeat

import ai.picovoice.eagle.EagleException
import ai.picovoice.eagle.EagleProfiler
import ai.picovoice.eagle.EagleProfilerEnrollFeedback
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.frontieraudio.heartbeat.BuildConfig
import com.frontieraudio.heartbeat.R
import com.frontieraudio.heartbeat.audio.SpeechSegmentAssembler
import com.frontieraudio.heartbeat.speaker.VoiceProfile
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var promptText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var primaryButton: Button
    private lateinit var cancelButton: Button

    private val voiceProfileStore by lazy { VoiceProfileStore(applicationContext) }

    private var recordingJob: Job? = null
    private var samplesCaptured = 0

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            statusText.text = getString(R.string.enrollment_permission_denied)
            setRecordingUi(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enrollment)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText = findViewById(R.id.enrollmentStatus)
        promptText = findViewById(R.id.enrollmentPrompt)
        progressText = findViewById(R.id.enrollmentProgressText)
        progressBar = findViewById(R.id.enrollmentProgressBar)
        primaryButton = findViewById(R.id.enrollmentPrimaryButton)
        cancelButton = findViewById(R.id.enrollmentCancelButton)

        progressBar.max = 100
        progressBar.progress = 0

        primaryButton.setOnClickListener {
            if (recordingJob?.isActive == true) {
                stopRecording()
            } else {
                ensurePermissionThenStart()
            }
        }

        cancelButton.setOnClickListener {
            stopRecording()
            finish()
        }

        updateProgress(0f)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }

    private fun ensurePermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (recordingJob?.isActive == true) return
        val accessKey = try {
            requireAccessKey()
        } catch (iae: IllegalArgumentException) {
            statusText.text = getString(R.string.enrollment_access_key_missing)
            return
        }
        samplesCaptured = 0
        statusText.text = getString(R.string.enrollment_listening_status)
        setRecordingUi(true)
        updateProgress(0f)

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val profiler = try {
                EagleProfiler.Builder()
                    .setAccessKey(accessKey)
                    .build(applicationContext)
            } catch (e: EagleException) {
                withContext(Dispatchers.Main) {
                    statusText.text = getString(R.string.enrollment_profiler_error, e.message ?: "")
                    setRecordingUi(false)
                }
                return@launch
            }

            val sampleRate = SampleRate.SAMPLE_RATE_16K
            val frameSize = FrameSize.FRAME_SIZE_512
            val audioRecord = createAudioRecord(sampleRate, frameSize)
            val vad = createVad(sampleRate, frameSize)
            val frameBuffer = ShortArray(frameSize.value)
            val assembler = SpeechSegmentAssembler(
                sampleRateHz = sampleRate.value,
                frameSizeSamples = frameSize.value,
                preRollDurationMs = ENROLLMENT_PREROLL_MS,
                minSegmentDurationMs = ENROLLMENT_MIN_SEGMENT_MS
            ) { segment ->
                try {
                    val result = profiler.enroll(segment.samples)
                    samplesCaptured += segment.samples.size
                    withContext(Dispatchers.Main) {
                        handleEnrollProgress(result.percentage, result.feedback)
                    }
                } catch (e: EagleException) {
                    Log.w(TAG, "Enrollment frame rejected", e)
                    withContext(Dispatchers.Main) {
                        statusText.text = getString(R.string.enrollment_frame_error, e.message ?: "")
                    }
                }
            }

            try {
                audioRecord.startRecording()
                var frameOffset = 0
                while (isActive) {
                    val read = audioRecord.read(
                        frameBuffer,
                        frameOffset,
                        frameBuffer.size - frameOffset,
                        AudioRecord.READ_BLOCKING
                    )
                    if (read < 0) {
                        throw IllegalStateException("AudioRecord read failed with code $read")
                    }
                    if (read == 0) continue
                    frameOffset += read
                    if (frameOffset >= frameBuffer.size) {
                        val frameCopy = frameBuffer.copyOf()
                        val isSpeech = vad.isSpeech(frameBuffer)
                        val timestampNs = SystemClock.elapsedRealtimeNanos()
                        assembler.onFrame(frameCopy, isSpeech, timestampNs)
                        frameOffset = 0
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    statusText.text = getString(R.string.enrollment_error_generic)
                }
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: IllegalStateException) {
                }
                audioRecord.release()
                vad.close()
                assembler.reset()

                val progress = withContext(Dispatchers.Main) { progressBar.progress }
                if (progress >= 100) {
                    val exportedProfile = exportProfile(profiler)
                    withContext(Dispatchers.Main) {
                        finalizeEnrollment(exportedProfile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = getString(R.string.enrollment_incomplete)
                        setRecordingUi(false)
                    }
                    safeDeleteProfiler(profiler)
                }

                withContext(NonCancellable + Dispatchers.Main) {
                    recordingJob = null
                }
            }
        }
    }

    private suspend fun finalizeEnrollment(profile: VoiceProfile?) {
        if (profile == null) {
            statusText.text = getString(R.string.enrollment_error_generic)
            setRecordingUi(false)
            return
        }
        withContext(Dispatchers.IO) {
            voiceProfileStore.saveVoiceProfile(profile)
        }
        statusText.text = getString(R.string.enrollment_complete)
        promptText.text = getString(R.string.enrollment_complete_prompt)
        updateProgress(100f)
        primaryButton.text = getString(R.string.enrollment_restart)
        setRecordingUi(false)
    }

    private fun exportProfile(profiler: EagleProfiler): VoiceProfile? {
        return try {
            val profile = profiler.export()
            val bytes = profile.bytes
            profile.delete()
            VoiceProfile(
                profileBytes = bytes,
                createdAtMillis = System.currentTimeMillis(),
                sampleRateHz = profiler.sampleRate,
                minEnrollSamples = profiler.minEnrollSamples,
                engineVersion = profiler.version,
                samplesCaptured = samplesCaptured
            )
        } catch (e: EagleException) {
            Log.e(TAG, "Unable to export Eagle profile", e)
            null
        } finally {
            safeDeleteProfiler(profiler)
        }
    }

    private fun safeDeleteProfiler(profiler: EagleProfiler) {
        try {
            profiler.delete()
        } catch (_: Throwable) {
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        setRecordingUi(false)
    }

    private fun setRecordingUi(active: Boolean) {
        primaryButton.text = if (active) {
            getString(R.string.enrollment_stop)
        } else {
            getString(R.string.enrollment_start)
        }
        cancelButton.isEnabled = true
    }

    private fun handleEnrollProgress(percentage: Float, feedback: EagleProfilerEnrollFeedback) {
        updateProgress(percentage)
        val statusMessage = when (feedback) {
            EagleProfilerEnrollFeedback.AUDIO_OK -> getString(R.string.enrollment_feedback_audio_ok)
            EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT -> getString(R.string.enrollment_feedback_too_short)
            EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER -> getString(R.string.enrollment_feedback_unknown_speaker)
            EagleProfilerEnrollFeedback.NO_VOICE_FOUND -> getString(R.string.enrollment_feedback_no_voice)
            EagleProfilerEnrollFeedback.QUALITY_ISSUE -> getString(R.string.enrollment_feedback_quality_issue)
        }
        statusText.text = getString(R.string.enrollment_progress_status, statusMessage, percentage.toInt())
    }

    private fun updateProgress(percentage: Float) {
        progressBar.progress = percentage.toInt().coerceIn(0, 100)
        progressText.text = getString(R.string.enrollment_progress_percent, percentage.toInt())
    }

    private fun requireAccessKey(): String {
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        require(accessKey.isNotBlank())
        return accessKey
    }

    private fun createAudioRecord(sampleRate: SampleRate, frameSize: FrameSize): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate.value,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer, frameSize.value * BYTES_PER_SAMPLE)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate.value)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun createVad(sampleRate: SampleRate, frameSize: FrameSize): VadSilero {
        return Vad.builder()
            .setContext(applicationContext)
            .setSampleRate(sampleRate)
            .setFrameSize(frameSize)
            .setMode(Mode.NORMAL)
            .setSpeechDurationMs(VAD_SPEECH_MS)
            .setSilenceDurationMs(VAD_SILENCE_MS)
            .build()
    }

    companion object {
        private const val TAG = "EnrollmentActivity"
        private const val ENROLLMENT_MIN_SEGMENT_MS = 800
        private const val ENROLLMENT_PREROLL_MS = 240
        private const val VAD_SPEECH_MS = 60
        private const val VAD_SILENCE_MS = 200
        private const val BYTES_PER_SAMPLE = 2
    }
}
