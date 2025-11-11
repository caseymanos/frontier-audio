package com.frontieraudio.heartbeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfig
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfigStore
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var openEnrollmentButton: Button
    private lateinit var clearEnrollmentButton: Button
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var thresholdValue: TextView
    private lateinit var retentionSeekBar: SeekBar
    private lateinit var retentionValue: TextView
    private lateinit var cooldownSeekBar: SeekBar
    private lateinit var cooldownValue: TextView

    private val voiceProfileStore by lazy { VoiceProfileStore(applicationContext) }
    private val configStore by lazy { SpeakerVerificationConfigStore(applicationContext) }

    private var baseStatusMessage: String = ""
    private var profileStatusMessage: String = ""
    private var latestConfig: SpeakerVerificationConfig = SpeakerVerificationConfig()

    private var updatingThresholdFromConfig = false
    private var updatingRetentionFromConfig = false
    private var updatingCooldownFromConfig = false

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startHeartbeatService()
                updateStatusText(getString(R.string.foreground_notification_text))
                requestNotificationPermissionIfNeeded()
            } else {
                updateStatusText(getString(R.string.permission_denied_message))
            }
        }

    private val postNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                updateStatusText(getString(R.string.notifications_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        configureSeekBars()
        configureButtons()
        observeStores()
        ensurePermissionThenStart()
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionThenStart()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        openEnrollmentButton = findViewById(R.id.openEnrollmentButton)
        clearEnrollmentButton = findViewById(R.id.clearEnrollmentButton)
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar)
        thresholdValue = findViewById(R.id.thresholdValue)
        retentionSeekBar = findViewById(R.id.retentionSeekBar)
        retentionValue = findViewById(R.id.retentionValue)
        cooldownSeekBar = findViewById(R.id.cooldownSeekBar)
        cooldownValue = findViewById(R.id.cooldownValue)
    }

    private fun configureButtons() {
        openEnrollmentButton.setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }
        clearEnrollmentButton.setOnClickListener {
            lifecycleScope.launch {
                voiceProfileStore.clearVoiceProfile()
            }
        }
    }

    private fun configureSeekBars() {
        thresholdSeekBar.max = THRESHOLD_PROGRESS_STEPS
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = thresholdValueFor(progress)
                updateThresholdValue(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (updatingThresholdFromConfig) return
                val value = thresholdValueFor(seekBar.progress)
                lifecycleScope.launch {
                    configStore.updateMatchThreshold(value)
                }
            }
        })

        retentionSeekBar.max = RETENTION_PROGRESS_MAX
        retentionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val millis = retentionMillisFor(progress)
                updateRetentionValue(millis)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (updatingRetentionFromConfig) return
                val newRetention = retentionMillisFor(seekBar.progress)
                lifecycleScope.launch {
                    configStore.updateTiming(
                        positiveRetention = newRetention,
                        negativeCooldown = latestConfig.negativeCooldownMillis
                    )
                }
            }
        })

        cooldownSeekBar.max = COOLDOWN_PROGRESS_MAX
        cooldownSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val millis = cooldownMillisFor(progress)
                updateCooldownValue(millis)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (updatingCooldownFromConfig) return
                val newCooldown = cooldownMillisFor(seekBar.progress)
                lifecycleScope.launch {
                    configStore.updateTiming(
                        positiveRetention = latestConfig.positiveRetentionMillis,
                        negativeCooldown = newCooldown
                    )
                }
            }
        })
    }

    private fun observeStores() {
        lifecycleScope.launch {
            voiceProfileStore.voiceProfileFlow.collectLatest { profile ->
                profileStatusMessage = if (profile != null) {
                    val details = getString(
                        R.string.profile_enrolled_details,
                        profile.engineVersion.ifBlank { "-" },
                        profile.samplesCaptured
                    )
                    listOf(getString(R.string.profile_enrolled), details).joinToString(separator = "\n")
                } else {
                    getString(R.string.profile_not_enrolled)
                }
                clearEnrollmentButton.isEnabled = profile != null
                renderStatus()
            }
        }

        lifecycleScope.launch {
            configStore.config.collectLatest { config ->
                latestConfig = config
                updatingThresholdFromConfig = true
                val thresholdProgress = thresholdProgressFor(config.matchThreshold)
                thresholdSeekBar.progress = thresholdProgress
                updateThresholdValue(config.matchThreshold)
                updatingThresholdFromConfig = false

                updatingRetentionFromConfig = true
                val retentionProgress = retentionProgressFor(config.positiveRetentionMillis)
                retentionSeekBar.progress = retentionProgress
                updateRetentionValue(config.positiveRetentionMillis)
                updatingRetentionFromConfig = false

                updatingCooldownFromConfig = true
                val cooldownProgress = cooldownProgressFor(config.negativeCooldownMillis)
                cooldownSeekBar.progress = cooldownProgress
                updateCooldownValue(config.negativeCooldownMillis)
                updatingCooldownFromConfig = false
            }
        }
    }

    private fun ensurePermissionThenStart() {
        val statusText = if (isRecordAudioGranted()) {
            startHeartbeatService()
            requestNotificationPermissionIfNeeded()
            getString(R.string.foreground_notification_text)
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            getString(R.string.requesting_permission_message)
        }
        updateStatusText(statusText)
    }

    private fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startHeartbeatService() {
        HeartbeatService.start(this)
    }

    private fun updateStatusText(message: String) {
        baseStatusMessage = message
        renderStatus()
    }

    private fun renderStatus() {
        val segments = buildList {
            if (baseStatusMessage.isNotBlank()) add(baseStatusMessage)
            if (profileStatusMessage.isNotBlank()) add(profileStatusMessage)
        }
        statusText.text = segments.joinToString(separator = "\n")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateThresholdValue(value: Float) {
        thresholdValue.text = String.format(Locale.US, "%.2f", value)
    }

    private fun updateRetentionValue(millis: Long) {
        retentionValue.text = String.format(Locale.US, "%.1f s", millis / 1000f)
    }

    private fun updateCooldownValue(millis: Long) {
        cooldownValue.text = String.format(Locale.US, "%.1f s", millis / 1000f)
    }

    private fun thresholdProgressFor(value: Float): Int {
        val clamped = value.coerceIn(
            SpeakerVerificationConfig.MIN_THRESHOLD,
            SpeakerVerificationConfig.MAX_THRESHOLD
        )
        return ((clamped - SpeakerVerificationConfig.MIN_THRESHOLD) * THRESHOLD_SCALE).roundToInt()
    }

    private fun thresholdValueFor(progress: Int): Float {
        val normalized = progress.coerceIn(0, THRESHOLD_PROGRESS_STEPS) / THRESHOLD_SCALE.toFloat()
        return SpeakerVerificationConfig.MIN_THRESHOLD + normalized
    }

    private fun retentionProgressFor(millis: Long): Int {
        val steps = (millis / RETENTION_STEP_MILLIS).toInt()
            .coerceIn(RETENTION_MIN_STEPS, RETENTION_MAX_STEPS)
        return steps - RETENTION_MIN_STEPS
    }

    private fun retentionMillisFor(progress: Int): Long {
        val steps = (RETENTION_MIN_STEPS + progress).coerceIn(RETENTION_MIN_STEPS, RETENTION_MAX_STEPS)
        return steps.toLong() * RETENTION_STEP_MILLIS
    }

    private fun cooldownProgressFor(millis: Long): Int {
        val steps = (millis / COOLDOWN_STEP_MILLIS).toInt()
            .coerceIn(COOLDOWN_MIN_STEPS, COOLDOWN_MAX_STEPS)
        return steps
    }

    private fun cooldownMillisFor(progress: Int): Long {
        val steps = progress.coerceIn(COOLDOWN_MIN_STEPS, COOLDOWN_MAX_STEPS)
        return steps.toLong() * COOLDOWN_STEP_MILLIS
    }

    companion object {
        private const val THRESHOLD_SCALE = 100
        private val THRESHOLD_PROGRESS_STEPS =
            ((SpeakerVerificationConfig.MAX_THRESHOLD - SpeakerVerificationConfig.MIN_THRESHOLD) * THRESHOLD_SCALE).roundToInt()

        private const val RETENTION_STEP_MILLIS = 500L
        private const val RETENTION_MIN_STEPS = 2  // 1 second
        private const val RETENTION_MAX_STEPS = 40 // 20 seconds
        private val RETENTION_PROGRESS_MAX = RETENTION_MAX_STEPS - RETENTION_MIN_STEPS

        private const val COOLDOWN_STEP_MILLIS = 500L
        private const val COOLDOWN_MIN_STEPS = 0
        private const val COOLDOWN_MAX_STEPS = 20 // 10 seconds
        private const val COOLDOWN_PROGRESS_MAX = COOLDOWN_MAX_STEPS
    }
}
