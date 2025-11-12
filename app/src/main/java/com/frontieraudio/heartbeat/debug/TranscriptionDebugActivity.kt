package com.frontieraudio.heartbeat.debug

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.frontieraudio.heartbeat.HeartbeatApplication
import com.frontieraudio.heartbeat.HeartbeatService
import com.frontieraudio.heartbeat.speaker.VoiceProfile
import com.frontieraudio.heartbeat.speaker.SpeakerVerificationConfig
import com.frontieraudio.heartbeat.transcription.TranscriptionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TranscriptionDebugActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TranscriptionDebugActivity"
    }

    private lateinit var debugText: TextView
    private lateinit var refreshButton: Button

    private val voiceProfileStore by lazy { (application as HeartbeatApplication).voiceProfileStore }
    private val configStore by lazy { (application as HeartbeatApplication).configStore }
    private val transcriptionConfigStore by lazy { (application as HeartbeatApplication).transcriptionConfigStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        try {
            debugText = TextView(this).apply {
                setPadding(32, 32, 32, 32)
                textSize = 14f
            }

            refreshButton = Button(this).apply {
                text = "Refresh Status"
                setOnClickListener { refreshStatus() }
            }

            // Simple layout
            val scrollView = android.widget.ScrollView(this)
            scrollView.addView(debugText)

            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(refreshButton)
                addView(
                    scrollView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply { setMargins(0, 16, 0, 0) })
            }

            setContentView(layout)
            title = "Transcription Debug"

            Log.d(TAG, "UI initialized, calling refreshStatus")
            refreshStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            debugText.text = "ERROR: ${e.message}\n\n${Log.getStackTraceString(e)}"
        }
    }

    private fun refreshStatus() {
        Log.d(TAG, "refreshStatus called")
        lifecycleScope.launch {
            try {
                val status = buildString {
                    appendLine("=== TRANSCRIPTION DEBUG STATUS ===\n")

                    // Voice Profile Status
                    appendLine("1. VOICE PROFILE STATUS:")
                    val profile = voiceProfileStore.getVoiceProfile()
                    if (profile != null) {
                        appendLine("   ✅ Profile loaded: ${profile.id}")
                        appendLine("   📊 Samples captured: ${profile.samplesCaptured}")
                        appendLine("   🎵 Sample rate: ${profile.sampleRateHz}Hz")
                        appendLine("   📅 Created: ${profile.createdAtMillis}")
                        appendLine("   🔧 Engine: ${profile.engineVersion}")
                    } else {
                        appendLine("   ❌ No voice profile found")
                        appendLine("   💡 Enroll a voice profile first")
                    }
                    appendLine()

                    // Verification Config
                    appendLine("2. VERIFICATION CONFIG:")
                    val config = configStore.config.first()
                    appendLine("   🎚️ Threshold: ${config.matchThreshold}")
                    appendLine("   ⏱️ Positive retention: ${config.positiveRetentionMillis}ms")
                    appendLine("   ⏱️ Negative cooldown: ${config.negativeCooldownMillis}ms")
                    appendLine()

                    // Transcription Config
                    appendLine("3. TRANSCRIPTION CONFIG:")
                    val transConfig = transcriptionConfigStore.configFlow.first()
                    appendLine("   🔑 API Key configured: ${if (transConfig.cartesiaApiKey.isNotBlank()) "✅ Yes" else "❌ No"}")
                    if (transConfig.cartesiaApiKey.isNotBlank() && transConfig.cartesiaApiKey.length > 8) {
                        appendLine("   🔑 API Key prefix: ${transConfig.cartesiaApiKey.take(8)}...")
                    }
                    appendLine("   🌐 URL: ${transConfig.cartesiaUrl}")
                    appendLine("   🌍 Language: ${transConfig.language}")
                    appendLine("   📍 Location tracking: ${if (transConfig.enableLocationTracking) "✅ Enabled" else "❌ Disabled"}")
                    appendLine()

                    // Service Status
                    appendLine("4. SERVICE STATUS:")
                    val service = HeartbeatService.getInstance()
                    if (service != null) {
                        appendLine("   ✅ HeartbeatService running")
                        appendLine("   🔊 Verification state: ${service.verificationState()}")
                        appendLine("   📍 Current location: ${service.getCurrentLocation() ?: "❌ No location"}")

                        // Try to get WebSocket status (reflection since it's private)
                        try {
                            val cartesiaField = service.javaClass.getDeclaredField("cartesiaClient")
                            cartesiaField.isAccessible = true
                            val cartesiaClient = cartesiaField.get(service)
                            if (cartesiaClient != null) {
                                val isConnectedMethod = cartesiaClient.javaClass.getMethod("isConnected")
                                val isConnected = isConnectedMethod.invoke(cartesiaClient) as Boolean
                                appendLine("   🌐 WebSocket: ${if (isConnected) "✅ Connected" else "❌ Not Connected"}")
                            } else {
                                appendLine("   🌐 WebSocket: ❌ Client is null")
                            }
                        } catch (e: Exception) {
                            appendLine("   🌐 WebSocket: ⚠️ Status unavailable (${e.message})")
                        }
                    } else {
                        appendLine("   ❌ HeartbeatService not running")
                    }
                    appendLine()

                    // Diagnosis
                    appendLine("5. DIAGNOSIS:")
                    if (profile == null) {
                        appendLine("   ❌ ISSUE: No voice profile enrolled")
                        appendLine("   💡 SOLUTION: Go to main app and complete voice enrollment")
                    } else if (transConfig.cartesiaApiKey.isBlank()) {
                        appendLine("   ❌ ISSUE: Cartesia API key not configured")
                        appendLine("   💡 SOLUTION: API key should be set in BuildConfig")
                    } else {
                        appendLine("   ✅ Voice profile is enrolled and loaded")
                        appendLine("   ✅ API key is configured")

                        val service = HeartbeatService.getInstance()
                        if (service == null) {
                            appendLine("   ❌ ISSUE: HeartbeatService not running")
                            appendLine("   💡 SOLUTION: Start the app foreground service")
                        } else {
                            appendLine("   ✅ All basic components are ready")
                            appendLine("")
                            appendLine("   📋 NEXT STEPS:")
                            appendLine("   1. Speak to trigger voice verification")
                            appendLine("   2. Green card should appear when verified")
                            appendLine("   3. Check 'View Logs' for WebSocket connection")
                            appendLine("   4. Look for '✅ WebSocket connected' message")
                            appendLine("   5. Look for '📝 Transcription' results")
                        }
                    }
                    appendLine()

                    appendLine("=== END DEBUG STATUS ===")
                }

                Log.d(TAG, "Status built successfully, updating UI")
                debugText.text = status
            } catch (e: Exception) {
                Log.e(TAG, "Error in refreshStatus", e)
                debugText.text = "ERROR: ${e.message}\n\n${Log.getStackTraceString(e)}"
            }
        }
    }
}
