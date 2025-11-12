package com.frontieraudio.heartbeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.cardview.widget.CardView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.frontieraudio.heartbeat.diagnostics.DiagnosticsEvent
import com.frontieraudio.heartbeat.location.LocationData
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfig
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfigStore
import com.frontieraudio.heartbeat.speaker.VoiceProfileStore
import com.frontieraudio.heartbeat.transcription.TranscriptionResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private lateinit var debugButton: Button
    private lateinit var logViewerButton: Button
    private lateinit var bypassVerificationSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var bypassWarningText: TextView
    private lateinit var verificationCard: CardView
    private lateinit var verificationStatusText: TextView
    private lateinit var verificationSimilarityText: TextView
    private lateinit var verificationCountdownText: TextView
    private lateinit var verificationDiagnosticsText: TextView
    private lateinit var calibrationButton: Button
    private lateinit var calibrationStatusText: TextView
    private lateinit var transcriptionCard: androidx.cardview.widget.CardView
    private lateinit var transcriptionStatusText: TextView
    private lateinit var transcriptionResultText: TextView
    private lateinit var locationText: TextView
    private lateinit var liveTranscriptScrollView: ScrollView
    private lateinit var liveTranscriptText: TextView
    private lateinit var metricsButton: Button
    private lateinit var metricsDisplayText: TextView
    private lateinit var metricsScrollView: ScrollView

    private val voiceProfileStore by lazy { (application as HeartbeatApplication).voiceProfileStore }
    private val configStore by lazy { (application as HeartbeatApplication).configStore }

    private var baseStatusMessage: String = ""
    private var profileStatusMessage: String = ""
    private var latestConfig: SpeakerVerificationConfig = SpeakerVerificationConfig()

    private var audioEffectsWarning: String? = null
    private var latestSegmentDiagnostics: SegmentDiagnosticsUi? = null
    private var calibrationActive = false
    private var calibrationSuggestion: Float? = null
    private val calibrationSamples = mutableListOf<Float>()

    private var updatingThresholdFromConfig = false
    private var updatingRetentionFromConfig = false
    private var updatingCooldownFromConfig = false
    private var verificationStateJob: Job? = null
    private var countdownJob: Job? = null

    // Live transcript state
    private val liveTranscriptLines = mutableListOf<String>()
    private val maxLiveTranscriptLines = 50

    // Metrics state
    private var metricsVisible = false

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
            // Request location permissions after notification permission
            requestLocationPermissionsIfNeeded()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineLocationGranted || coarseLocationGranted) {
                Log.d("MainActivity", "Location permissions granted")
                updateStatusText("Location permissions granted - GPS tracking enabled")
            } else {
                Log.w("MainActivity", "Location permissions denied")
                updateStatusText("Location permissions denied - GPS tracking disabled")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        configureSeekBars()
        configureButtons()
        observeStores()
        observeVerificationState()
        observeTranscriptionResults()
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
        verificationCard = findViewById(R.id.verificationCard)
        verificationStatusText = findViewById(R.id.verificationStatusText)
        verificationSimilarityText = findViewById(R.id.verificationSimilarityText)
        verificationCountdownText = findViewById(R.id.verificationCountdownText)
        verificationDiagnosticsText = findViewById(R.id.verificationDiagnosticsText)
        verificationDiagnosticsText.isVisible = false
        calibrationButton = findViewById(R.id.calibrationButton)
        calibrationStatusText = findViewById(R.id.calibrationStatus)
        debugButton = findViewById(R.id.debugButton)
        logViewerButton = findViewById(R.id.logViewerButton)
        bypassVerificationSwitch = findViewById(R.id.bypassVerificationSwitch)
        bypassWarningText = findViewById(R.id.bypassWarningText)
        calibrationStatusText.isVisible = false

        // Transcription UI components
        transcriptionCard = findViewById(R.id.transcriptionCard)
        transcriptionStatusText = findViewById(R.id.transcriptionStatusText)
        transcriptionResultText = findViewById(R.id.transcriptionResultText)
        locationText = findViewById(R.id.locationText)

        // Live transcript UI components
        liveTranscriptScrollView = findViewById(R.id.liveTranscriptScrollView)
        liveTranscriptText = findViewById(R.id.liveTranscriptText)
        metricsButton = findViewById(R.id.metricsButton)
        metricsDisplayText = findViewById(R.id.metricsDisplayText)
        metricsScrollView = findViewById(R.id.metricsScrollView)

        updateVerificationIndicator(HeartbeatService.VerificationState(false, null, null))
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
        calibrationButton.setOnClickListener {
            when {
                calibrationActive -> cancelCalibration(showMessage = true)
                calibrationSuggestion != null -> applyCalibrationSuggestion()
                else -> startCalibration()
            }
        }
        debugButton.setOnClickListener {
            Log.d("MainActivity", "DEBUG BUTTON CLICKED")
            Toast.makeText(this, "Opening Debug Transcription...", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(this, com.frontieraudio.heartbeat.debug.TranscriptionDebugActivity::class.java))
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting TranscriptionDebugActivity", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                updateStatusText("Error: ${e.message}")
            }
        }
        logViewerButton.setOnClickListener {
            Log.d("MainActivity", "LOG VIEWER BUTTON CLICKED")
            Toast.makeText(this, "Opening Logs...", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(this, com.frontieraudio.heartbeat.debug.LogViewerActivity::class.java))
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting LogViewerActivity", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                updateStatusText("Error: ${e.message}")
            }
        }
        bypassVerificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            bypassWarningText.isVisible = isChecked
            HeartbeatService.getInstance()?.setBypassVerification(isChecked)
            if (isChecked) {
                updateStatusText("⚠️ TEST MODE: Speaker verification bypassed")
            } else {
                updateStatusText("Speaker verification enabled")
            }
        }
        metricsButton.setOnClickListener {
            toggleMetricsDisplay()
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
                Log.d(
                    "MainActivity",
                    "Received profile emission: ${if (profile != null) "loaded (${profile.samplesCaptured} samples)" else "null"}"
                )
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
                calibrationButton.isEnabled = profile != null
                if (profile == null) {
                    cancelCalibration(showMessage = false)
                }
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

    private fun observeVerificationState() {
        verificationStateJob?.cancel()
        verificationStateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateVerificationIndicator(HeartbeatService.VerificationState(false, null, null))
                clearDiagnostics()
                var activeService: HeartbeatService? = null
                var stateCollector: Job? = null
                var diagnosticsCollector: Job? = null
                try {
                    while (isActive) {
                        val service = HeartbeatService.getInstance()
                        when {
                            service != null && service !== activeService -> {
                                stateCollector?.cancel()
                                diagnosticsCollector?.cancel()
                                activeService = service
                                stateCollector = launch {
                                    service.verificationStateFlow().collectLatest { state ->
                                        updateVerificationIndicator(state)
                                    }
                                }
                                diagnosticsCollector = launch {
                                    service.diagnosticsFlow().collectLatest { event ->
                                        handleDiagnosticsEvent(event)
                                    }
                                }
                            }

                            service == null && activeService != null -> {
                                stateCollector?.cancel()
                                diagnosticsCollector?.cancel()
                                stateCollector = null
                                diagnosticsCollector = null
                                activeService = null
                                updateVerificationIndicator(HeartbeatService.VerificationState(false, null, null))
                                clearDiagnostics()
                            }
                        }
                        delay(SERVICE_RETRY_DELAY_MS)
                    }
                } finally {
                    stateCollector?.cancel()
                    diagnosticsCollector?.cancel()
                }
            }
        }
    }

    private fun observeTranscriptionResults() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var activeService: HeartbeatService? = null
                var transcriptionCollector: Job? = null
                try {
                    while (isActive) {
                        val service = HeartbeatService.getInstance()
                        when {
                            service != null && service !== activeService -> {
                                transcriptionCollector?.cancel()
                                activeService = service
                                Log.d("MainActivity", "Starting transcription result collection")
                                transcriptionCollector = launch {
                                    service.transcriptionResults().collectLatest { result ->
                                        Log.d("MainActivity", "Collected transcription result in MainActivity")
                                        handleTranscriptionResult(result)
                                    }
                                }
                            }

                            service == null && activeService != null -> {
                                transcriptionCollector?.cancel()
                                transcriptionCollector = null
                                activeService = null
                                updateTranscriptionStatus("No service connection")
                            }
                        }
                        delay(SERVICE_RETRY_DELAY_MS)
                    }
                } finally {
                    transcriptionCollector?.cancel()
                }
            }
        }
    }

    private fun handleTranscriptionResult(result: TranscriptionResult) {
        Log.d(
            "MainActivity",
            "Received transcription: '${result.transcript}', isFinal=${result.isFinal}, length=${result.transcript.length}"
        )

        // Update status based on whether this is partial or final
        if (result.isFinal) {
            updateTranscriptionStatus("✅ Transcription complete")
        } else {
            updateTranscriptionStatus("🎤 Transcribing...")
        }

        // Update transcription result text - show ALL results for debugging
        transcriptionResultText.isVisible = true

        if (result.transcript.isNotBlank()) {
            // Show confidence if available
            val confidenceText = if (result.confidence != null) {
                " (Confidence: ${(result.confidence * 100).toInt()}%)"
            } else {
                ""
            }

            val finalIndicator = if (result.isFinal) " [FINAL]" else " [PARTIAL]"
            transcriptionResultText.text = "${result.transcript}$confidenceText$finalIndicator"

            // Add to live transcript - only for final results
            if (result.isFinal) {
                addLiveTranscriptLine(result.transcript)
            }
        } else {
            // Show even empty results for debugging
            transcriptionResultText.text = "[Empty transcript received - isFinal=${result.isFinal}]"
            Log.w("MainActivity", "Received empty transcript from Stage 3")
        }

        // Update location if available
        result.locationData?.let { location ->
            locationText.text = formatLocation(location)
            locationText.isVisible = true
        } ?: run {
            locationText.isVisible = false
        }
    }

    private fun updateTranscriptionStatus(status: String) {
        transcriptionStatusText.text = status
    }

    private fun formatLocation(location: LocationData): String {
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lon = String.format(Locale.US, "%.6f", location.longitude)
        val accuracy = if (location.accuracy != null) {
            " (±${location.accuracy}m)"
        } else {
            ""
        }
        return "Location: $lat°, $lon°$accuracy"
    }

    private fun toggleMetricsDisplay() {
        metricsVisible = !metricsVisible
        if (metricsVisible) {
            showMetrics()
            metricsButton.text = "Hide Metrics"
        } else {
            hideMetrics()
            metricsButton.text = "Show Metrics"
        }
    }

    private fun showMetrics() {
        val service = HeartbeatService.getInstance()
        if (service == null) {
            metricsDisplayText.text = "Service not available"
            metricsScrollView.isVisible = true
            return
        }

        val collector = service.getMetricsCollector()
        val metrics = collector.getCompleted()
        val aggregate = collector.getAggregateStats()
        val activeCount = collector.getActiveTrackingCount()
        val activeIds = collector.getActiveSegmentIds()

        val display = buildString {
            aggregate?.let {
                appendLine(it.formatSummary())
                appendLine()
                appendLine("─".repeat(40))
                appendLine()
            } ?: run {
                appendLine("No metrics collected yet.")
                appendLine("Start speaking to collect metrics!")
                appendLine()
                appendLine("Debug Info:")
                appendLine("  Active tracking: $activeCount")
                if (activeIds.isNotEmpty()) {
                    appendLine("  Segment IDs: ${activeIds.joinToString()}")
                    appendLine("  (Waiting for transcription to complete...)")
                }
                appendLine()
                appendLine("Check logs with:")
                appendLine("  adb logcat | grep '📊'")
                appendLine()
            }

            if (metrics.isNotEmpty()) {
                appendLine("=== Recent Transcriptions ===")
                appendLine()
                metrics.takeLast(10).reversed().forEach { m ->
                    appendLine(m.formatSummary())
                    appendLine("─".repeat(40))
                    appendLine()
                }
            }
        }

        metricsDisplayText.text = display
        metricsScrollView.isVisible = true

        // Auto-scroll to top to see aggregate stats first
        metricsScrollView.post {
            metricsScrollView.scrollTo(0, 0)
        }
    }

    private fun hideMetrics() {
        metricsScrollView.isVisible = false
    }

    private fun addLiveTranscriptLine(transcript: String) {
        if (transcript.isBlank() || transcript.startsWith("[") || transcript.startsWith("Error:")) {
            return // Skip system messages and errors
        }

        // Add timestamp to each line
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val formattedLine = "[$timestamp] $transcript"

        // Add to transcript lines
        liveTranscriptLines.add(formattedLine)

        // Limit the number of lines to prevent memory issues
        if (liveTranscriptLines.size > maxLiveTranscriptLines) {
            liveTranscriptLines.removeAt(0)
        }

        // Update UI on main thread
        runOnUiThread {
            liveTranscriptText.text = liveTranscriptLines.joinToString("\n")

            // Auto-scroll to bottom
            liveTranscriptScrollView.post {
                liveTranscriptScrollView.fullScroll(ScrollView.FOCUS_DOWN)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestLocationPermissionsIfNeeded()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissionsIfNeeded()
            return
        }
        postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestLocationPermissionsIfNeeded() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationGranted || coarseLocationGranted) {
            Log.d("MainActivity", "Location permissions already granted")
            return
        }

        Log.d("MainActivity", "Requesting location permissions")
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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

    private fun updateVerificationIndicator(state: HeartbeatService.VerificationState) {
        val isVerified = state.isVerified
        val backgroundColorRes = if (isVerified) {
            R.color.verification_active
        } else {
            R.color.verification_inactive
        }

        verificationCard.setCardBackgroundColor(ContextCompat.getColor(this, backgroundColorRes))
        verificationStatusText.text = getString(
            if (isVerified) R.string.verification_active else R.string.verification_listening
        )

        val similarityValue = state.similarity ?: 0f
        verificationSimilarityText.text = getString(R.string.verification_similarity, similarityValue)

        if (isVerified && state.validUntilElapsedRealtimeMs != null) {
            startCountdownTimer(state.validUntilElapsedRealtimeMs)
        } else {
            cancelCountdownTimer()
        }
    }

    private fun startCountdownTimer(validUntilMs: Long) {
        countdownJob?.cancel()
        val job = lifecycleScope.launch {
            while (isActive) {
                val remainingMs = validUntilMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0) {
                    verificationCountdownText.text = getString(R.string.verification_expired)
                    break
                }
                val remainingSeconds = remainingMs / 1000f
                verificationCountdownText.text =
                    getString(R.string.verification_countdown, remainingSeconds)
                delay(COUNTDOWN_UPDATE_INTERVAL_MS)
            }
        }
        job.invokeOnCompletion {
            if (countdownJob === job) {
                countdownJob = null
            }
        }
        countdownJob = job
    }

    private fun cancelCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = null
        verificationCountdownText.text = ""  // Empty instead of "Expired"
    }

    override fun onDestroy() {
        verificationStateJob?.cancel()
        verificationStateJob = null
        countdownJob?.cancel()
        countdownJob = null
        super.onDestroy()
    }

    private fun handleDiagnosticsEvent(event: DiagnosticsEvent) {
        when (event) {
            is DiagnosticsEvent.AudioEffectsWarning -> {
                if (event.source == DiagnosticsEvent.Source.VERIFICATION) {
                    audioEffectsWarning = if (event.activeEffects.isEmpty()) {
                        null
                    } else {
                        getString(R.string.diagnostics_audio_effects, event.activeEffects.joinToString())
                    }
                }
            }

            is DiagnosticsEvent.SegmentMetrics -> {
                if (event.source == DiagnosticsEvent.Source.VERIFICATION) {
                    val summary = getString(
                        R.string.diagnostics_segment_metrics,
                        event.rmsDbfs,
                        event.clippingRatio * 100f,
                        event.speechRatio * 100f
                    )
                    latestSegmentDiagnostics = SegmentDiagnosticsUi(summary, event.warnings)
                    handleCalibrationSegment(event)
                }
            }
        }
        updateDiagnosticsText()
    }

    private fun handleCalibrationSegment(event: DiagnosticsEvent.SegmentMetrics) {
        if (!calibrationActive) return
        val similarity = event.similarity ?: return
        if (event.matched == true) {
            calibrationStatusText.isVisible = true
            calibrationStatusText.text = getString(R.string.calibration_status_matched, similarity)
            return
        }
        recordCalibrationSample(similarity)
    }

    private fun startCalibration() {
        calibrationActive = true
        calibrationSamples.clear()
        calibrationSuggestion = null
        calibrationButton.text = getString(R.string.calibration_button_cancel)
        calibrationStatusText.isVisible = true
        calibrationStatusText.text = getString(R.string.calibration_status_start, CALIBRATION_TARGET_SAMPLES)
    }

    private fun cancelCalibration(showMessage: Boolean) {
        val wasActive = calibrationActive || calibrationSuggestion != null
        calibrationActive = false
        calibrationSuggestion = null
        calibrationSamples.clear()
        calibrationButton.text = getString(R.string.calibration_button_start)
        if (showMessage && wasActive) {
            calibrationStatusText.isVisible = true
            calibrationStatusText.text = getString(R.string.calibration_status_cancelled)
        } else {
            calibrationStatusText.isVisible = false
        }
    }

    private fun recordCalibrationSample(similarity: Float) {
        if (!calibrationActive) return
        calibrationSamples.add(similarity)
        calibrationStatusText.isVisible = true
        calibrationStatusText.text = getString(
            R.string.calibration_status_progress,
            calibrationSamples.size,
            CALIBRATION_TARGET_SAMPLES,
            similarity
        )
        if (calibrationSamples.size >= CALIBRATION_TARGET_SAMPLES) {
            finishCalibration()
        }
    }

    private fun finishCalibration() {
        calibrationActive = false
        if (calibrationSamples.isEmpty()) {
            calibrationStatusText.isVisible = false
            calibrationButton.text = getString(R.string.calibration_button_start)
            return
        }
        val mean = calibrationSamples.average().toFloat()
        val variance = calibrationSamples.fold(0f) { acc, sample ->
            val delta = sample - mean
            acc + delta * delta
        } / calibrationSamples.size
        val stdDev = sqrt(variance.toDouble()).toFloat()
        val suggestion = (mean + 3f * stdDev).coerceIn(
            SpeakerVerificationConfig.MIN_THRESHOLD,
            SpeakerVerificationConfig.MAX_THRESHOLD
        )
        calibrationSuggestion = suggestion
        calibrationSamples.clear()
        calibrationButton.text = getString(R.string.calibration_button_apply, suggestion)
        calibrationStatusText.isVisible = true
        calibrationStatusText.text = getString(
            R.string.calibration_status_complete,
            mean,
            stdDev,
            suggestion
        )
    }

    private fun applyCalibrationSuggestion() {
        val suggestion = calibrationSuggestion ?: return
        calibrationActive = false
        calibrationSamples.clear()
        calibrationSuggestion = null
        calibrationButton.text = getString(R.string.calibration_button_start)
        lifecycleScope.launch {
            configStore.updateMatchThreshold(suggestion)
        }
        calibrationStatusText.isVisible = true
        calibrationStatusText.text = getString(R.string.calibration_status_applied, suggestion)
    }

    private fun updateDiagnosticsText() {
        val lines = mutableListOf<String>()
        audioEffectsWarning?.let { lines.add(it) }
        latestSegmentDiagnostics?.let { diagnostics ->
            lines.add(diagnostics.summary)
            if (diagnostics.warnings.isNotEmpty()) {
                lines.add(
                    getString(
                        R.string.diagnostics_warnings_prefix,
                        diagnostics.warnings.joinToString()
                    )
                )
            }
        }
        if (lines.isEmpty()) {
            verificationDiagnosticsText.isVisible = false
            verificationDiagnosticsText.text = ""
            return
        }
        val hasWarnings = audioEffectsWarning != null || (latestSegmentDiagnostics?.warnings?.isNotEmpty() == true)
        val colorRes = if (hasWarnings) R.color.diagnostic_warning else R.color.diagnostic_neutral
        verificationDiagnosticsText.isVisible = true
        verificationDiagnosticsText.setTextColor(ContextCompat.getColor(this, colorRes))
        verificationDiagnosticsText.text = lines.joinToString(separator = "\n")
    }

    private fun clearDiagnostics() {
        audioEffectsWarning = null
        latestSegmentDiagnostics = null
        updateDiagnosticsText()
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

    private data class SegmentDiagnosticsUi(
        val summary: String,
        val warnings: List<String>
    )

    companion object {
        private const val THRESHOLD_SCALE = 100
        private val THRESHOLD_PROGRESS_STEPS =
            ((SpeakerVerificationConfig.MAX_THRESHOLD - SpeakerVerificationConfig.MIN_THRESHOLD) * THRESHOLD_SCALE).roundToInt()
        private const val CALIBRATION_TARGET_SAMPLES = 8

        private const val RETENTION_STEP_MILLIS = 500L
        private const val RETENTION_MIN_STEPS = 2  // 1 second
        private const val RETENTION_MAX_STEPS = 40 // 20 seconds
        private val RETENTION_PROGRESS_MAX = RETENTION_MAX_STEPS - RETENTION_MIN_STEPS

        private const val COOLDOWN_STEP_MILLIS = 500L
        private const val COOLDOWN_MIN_STEPS = 0
        private const val COOLDOWN_MAX_STEPS = 20 // 10 seconds
        private const val COOLDOWN_PROGRESS_MAX = COOLDOWN_MAX_STEPS

        private const val COUNTDOWN_UPDATE_INTERVAL_MS = 100L
        private const val SERVICE_RETRY_DELAY_MS = 500L
    }
}
