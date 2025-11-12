package com.frontieraudio.heartbeat.debug

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LogViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LogViewerActivity"
    }

    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var backButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var liveButton: Button
    private var liveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        try {
            logText = TextView(this).apply {
                setPadding(16, 16, 16, 16)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            scrollView = ScrollView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { weight = 1f }
                addView(logText)
            }

            backButton = Button(this).apply {
                text = "Back"
                setOnClickListener { finish() }
            }

            refreshButton = Button(this).apply {
                text = "Refresh Logs"
                setOnClickListener { refreshLogs() }
            }

            clearButton = Button(this).apply {
                text = "Clear Logs"
                setOnClickListener { clearLogs() }
            }

            liveButton = Button(this).apply {
                text = "Play Live"
                setOnClickListener { toggleLivePlayback() }
            }

            val buttonLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(
                    backButton, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { setMargins(0, 8, 4, 8) })
                addView(
                    refreshButton, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { setMargins(4, 8, 4, 8) })
                addView(
                    clearButton, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { setMargins(4, 8, 4, 8) })
                addView(
                    liveButton, android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { setMargins(4, 8, 0, 8) })
            }

            val mainLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(buttonLayout)
                addView(scrollView)
            }

            setContentView(mainLayout)
            title = "Heartbeat Logs"

            Log.d(TAG, "UI initialized, calling refreshLogs")
            refreshLogs()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            logText.text = "ERROR: ${e.message}\n\n${Log.getStackTraceString(e)}"
        }
    }

    private fun refreshLogs() {
        Log.d(TAG, "refreshLogs called")
        lifecycleScope.launch {
            try {
                logText.text = "Loading in-app logs..."

                val logs = AppLogger.getRecentLogs(300)
                val stats = AppLogger.getLogStats()

                val displayText = buildString {
                    appendLine("=== HEARTBEAT APP LOGS ===")
                    appendLine("Stats: $stats")
                    appendLine(
                        "Last updated: ${
                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date())
                        }"
                    )
                    appendLine()

                    if (logs.isEmpty()) {
                        appendLine("No logs yet. Try speaking to trigger activity!")
                        appendLine()
                        appendLine("Expected activity:")
                        appendLine("- Stage 1: VAD speech detection")
                        appendLine("- Stage 2: Speaker verification (green card)")
                        appendLine("- Stage 3: Cartesia WebSocket transcription")
                    } else {
                        appendLine("Recent logs (showing last ${logs.size} entries):")
                        appendLine()
                        logs.forEach { appendLine(it) }
                    }
                }

                Log.d(TAG, "Logs loaded successfully, updating UI with ${logs.size} entries")
                logText.text = displayText

                // Auto-scroll to bottom
                scrollView.post {
                    scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in refreshLogs", e)
                logText.text = "Error loading logs: ${e.message}\n\n${Log.getStackTraceString(e)}"
            }
        }
    }


    private fun clearLogs() {
        Log.d(TAG, "clearLogs called")
        lifecycleScope.launch {
            try {
                AppLogger.clearLogs()
                logText.text = "In-app logs cleared. Refresh to see new logs."
                Log.d(TAG, "Logs cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in clearLogs", e)
                logText.text = "Error clearing logs: ${e.message}\n\n${Log.getStackTraceString(e)}"
            }
        }
    }

    private fun toggleLivePlayback() {
        if (liveJob == null) {
            startLivePlayback()
        } else {
            stopLivePlayback()
        }
    }

    private fun startLivePlayback() {
        Log.d(TAG, "startLivePlayback called")
        liveButton.text = "Stop Live"
        if (logText.text.isEmpty()) {
            refreshLogs()
        }
        liveJob = lifecycleScope.launch {
            appendLogLine("--- Live playback started ---")
            AppLogger.logFlow.collect { entry ->
                appendLogLine(entry)
            }
        }
    }

    private fun stopLivePlayback() {
        Log.d(TAG, "stopLivePlayback called")
        val wasRunning = liveJob != null
        liveJob?.cancel()
        liveJob = null
        liveButton.text = "Play Live"
        if (wasRunning) {
            appendLogLine("--- Live playback stopped ---")
        }
    }

    private fun appendLogLine(line: String) {
        if (logText.text.isEmpty()) {
            logText.text = line
        } else {
            logText.append("\n$line")
        }
        scrollView.post {
            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && liveJob != null) {
            stopLivePlayback()
        }
    }
}
