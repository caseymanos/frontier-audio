package com.frontieraudio.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed; restarting HeartbeatService.")
            HeartbeatService.start(context)
        }
    }

    companion object {
        private const val TAG = "Heartbeat"
    }
}


