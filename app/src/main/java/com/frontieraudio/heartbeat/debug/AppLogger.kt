package com.frontieraudio.heartbeat.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {
    private const val maxLogEntries = 500
    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private val _logFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String,
        val level: String
    ) {
        override fun toString(): String {
            return "$timestamp $level/$tag: $message"
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) {
        logEntry("DEBUG", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        logEntry("INFO", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        logEntry("WARN", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        logEntry("ERROR", tag, fullMessage)
        Log.e(tag, message, throwable)
    }

    private fun logEntry(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, tag, message, level)

        logEntries.offer(entry)

        while (logEntries.size > maxLogEntries) {
            logEntries.poll()
        }

        _logFlow.tryEmit(entry.toString())
    }

    fun getRecentLogs(count: Int = 200): List<String> {
        if (count <= 0) return emptyList()

        return logEntries
            .toList()
            .takeLast(count)
            .map { it.toString() }
    }

    fun clearLogs() {
        logEntries.clear()
        _logFlow.tryEmit("--- Logs Cleared ---")
    }

    fun getLogStats(): String {
        return "Total entries: ${logEntries.size} / $maxLogEntries max"
    }
}
