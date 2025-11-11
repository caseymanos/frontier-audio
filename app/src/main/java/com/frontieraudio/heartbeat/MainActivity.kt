package com.frontieraudio.heartbeat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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
        ensurePermissionThenStart()
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionThenStart()
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
        findViewById<TextView>(R.id.statusText)?.text = message
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
}


