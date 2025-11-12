package com.frontieraudio.heartbeat.transcription

import com.fasterxml.jackson.annotation.JsonProperty
import com.frontieraudio.heartbeat.debug.AppLogger
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.frontieraudio.heartbeat.SpeechSegment
import com.frontieraudio.heartbeat.speaker.VerifiedSpeechSegment
import com.frontieraudio.heartbeat.location.LocationData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// Data classes for WebSocket streaming protocol
data class WebSocketConfig(
    @JsonProperty("model") val model: String,
    @JsonProperty("language") val language: String?,
    @JsonProperty("encoding") val encoding: String = "pcm_s16le",
    @JsonProperty("sample_rate") val sampleRate: Int = 16000
)

data class EndOfStream(
    @JsonProperty("message") val message: String = "EOS"
)

class CartesiaWebSocketClient(
    private val config: TranscriptionConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val objectMapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var isConnecting = false

    fun isConnected(): Boolean = isConnected

    private val transcriptionResultsInternal = MutableSharedFlow<TranscriptionResult>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    val transcriptionResults: SharedFlow<TranscriptionResult> = transcriptionResultsInternal.asSharedFlow()

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            AppLogger.i(TAG, "✅ WebSocket connection opened successfully - ready to stream audio")
            isConnected = true
            isConnecting = false
            // No init message needed - all config is in query params
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            AppLogger.w(TAG, "Received unexpected binary message")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLogger.w(TAG, "WebSocket closing: $code - $reason")
            isConnected = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AppLogger.e(TAG, "WebSocket closed: $code - $reason")
            isConnected = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLogger.e(TAG, "❌ WebSocket failure: ${t.message}", t)
            isConnected = false
            isConnecting = false
            connectionJob?.cancel()
            connectionJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                if (isActive) {
                    AppLogger.i(TAG, "Attempting to reconnect WebSocket...")
                    connect()
                }
            }
        }
    }

    fun connect() {
        if (isConnected) {
            AppLogger.d(TAG, "Already connected")
            return
        }
        if (isConnecting) {
            AppLogger.d(TAG, "Already connecting")
            return
        }
        isConnecting = true
        val urlWithAuth =
            "${config.cartesiaUrl}?api_key=${config.cartesiaApiKey}&model=ink-whisper&language=${config.language}&encoding=pcm_s16le&sample_rate=16000"
        AppLogger.i(TAG, "Connecting to Cartesia WebSocket with all params in URL and Cartesia-Version header")
        AppLogger.i(
            TAG,
            "URL: ${config.cartesiaUrl}?api_key=***&model=ink-whisper&language=${config.language}&encoding=pcm_s16le&sample_rate=16000"
        )
        val request = Request.Builder()
            .url(urlWithAuth)
            .addHeader("Cartesia-Version", "2025-04-16")
            .build()

        webSocket = client.newWebSocket(request, webSocketListener)
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        scope.cancel()
        AppLogger.d(TAG, "Disconnected from Cartesia WebSocket")
    }


    private fun handleTextMessage(text: String) {
        AppLogger.d(TAG, "📥 Received WebSocket message: ${text.take(200)}...")
        try {
            val response: CartesiaResponse = objectMapper.readValue(text)
            when (response.type) {
                "transcript" -> {
                    if (response.text != null) {
                        AppLogger.d(TAG, "📝 Transcript received: '${response.text.take(50)}...'")
                        handleTranscriptionResponse(response)
                    } else {
                        AppLogger.w(TAG, "⚠️ Transcript message with null text")
                    }
                }

                "flush_done", "done" -> {
                    AppLogger.i(TAG, "✅ Received ${response.type} acknowledgment")
                }

                "error" -> {
                    val errorMsg = response.message ?: response.error ?: "Unknown error"
                    val errorCode = response.code ?: 0
                    AppLogger.e(TAG, "❌ Cartesia error ($errorCode): $errorMsg")
                }

                else -> {
                    if (response.text != null) {
                        AppLogger.d(TAG, "📝 Message with text (no explicit type): '${response.text.take(50)}...'")
                        handleTranscriptionResponse(response)
                    } else {
                        AppLogger.w(TAG, "⚠️ Received unhandled response type '${response.type}': ${text.take(100)}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing Cartesia response: $text", e)
        }
    }

    private fun handleTranscriptionResponse(response: CartesiaResponse) {
        val fullText = response.text ?: ""
        val isFinal = response.is_final ?: false
        AppLogger.i(
            TAG,
            "📝 Transcription [${if (isFinal) "FINAL" else "PARTIAL"}]: '$fullText' (${fullText.length} chars)"
        )

        val result = TranscriptionResult(
            segment = createDummyVerifiedSegment(),
            transcript = fullText,
            confidence = null,
            isFinal = isFinal,
            processingTimeMs = 0,
            locationData = null
        )
        transcriptionResultsInternal.tryEmit(result)
    }

    suspend fun startTranscriptionSession() {
        if (isConnected) {
            AppLogger.d(TAG, "Session start requested, already connected.")
            return
        }

        AppLogger.i(TAG, "Session start requested, connecting WebSocket...")
        connect()

        // Wait for connection to establish (with timeout)
        var attempts = 0
        while (!isConnected && attempts < 50) {
            delay(100) // Wait 100ms per attempt = 5 second max
            attempts++
        }

        if (isConnected) {
            AppLogger.i(TAG, "✅ WebSocket connected and ready for streaming (took ${attempts * 100}ms)")
        } else {
            AppLogger.e(TAG, "❌ WebSocket failed to connect after ${attempts * 100}ms timeout")
        }
    }

    fun streamAudioChunk(audioChunk: ShortArray) {
        if (!isConnected) {
            AppLogger.w(TAG, "⚠️ Not connected, cannot stream audio chunk (${audioChunk.size} samples)")
            return
        }
        scope.launch {
            try {
                val byteBuffer = ByteBuffer.allocate(audioChunk.size * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioChunk) {
                    byteBuffer.putShort(sample)
                }
                val audioBytes = byteBuffer.array()
                val sent = webSocket?.send(ByteString.of(*audioBytes))
                if (sent == true) {
                    AppLogger.d(TAG, "✅ Sent audio chunk: ${audioBytes.size} bytes (${audioChunk.size} samples)")
                } else {
                    AppLogger.w(TAG, "⚠️ Failed to send audio chunk (websocket send returned false)")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "❌ Error streaming audio chunk", e)
            }
        }
    }

    fun endTranscriptionSession() {
        if (!isConnected) {
            AppLogger.w(TAG, "Not connected, cannot end session.")
            return
        }
        scope.launch {
            try {
                webSocket?.send("finalize")
                AppLogger.i(TAG, "Sent finalize command.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error sending finalize command", e)
            }
        }
    }

    private fun createDummyVerifiedSegment(): VerifiedSpeechSegment {
        return VerifiedSpeechSegment(
            segment = SpeechSegment(
                id = -1,
                startTimestampNs = 0L,
                endTimestampNs = 0L,
                sampleRateHz = 16000,
                frameSizeSamples = 512,
                samples = ShortArray(0)
            ),
            similarity = 0f,
            scores = floatArrayOf(),
            verifiedAtMillis = System.currentTimeMillis()
        )
    }

    companion object {
        private const val TAG = "CartesiaWebSocket"
        private const val RECONNECT_DELAY_MS = 5000L
    }
}

// Data classes for JSON serialization/deserialization
data class CartesiaResponse(
    @JsonProperty("text") val text: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("is_final") val is_final: Boolean?,
    @JsonProperty("request_id") val request_id: String?,
    @JsonProperty("error") val error: String?,
    @JsonProperty("code") val code: Int?,
    @JsonProperty("segments") val segments: List<TranscriptionSegment>?,
    @JsonProperty("message") val message: String?,
    @JsonProperty("duration") val duration: Double?,
    @JsonProperty("words") val words: List<TranscriptionWord>?,
    @JsonProperty("language") val language: String?
)

data class TranscriptionSegment(
    @JsonProperty("text") val text: String,
    @JsonProperty("start") val start: Float?,
    @JsonProperty("end") val end: Float?
)

data class TranscriptionWord(
    @JsonProperty("word") val word: String,
    @JsonProperty("start") val start: Double?,
    @JsonProperty("end") val end: Double?
)
