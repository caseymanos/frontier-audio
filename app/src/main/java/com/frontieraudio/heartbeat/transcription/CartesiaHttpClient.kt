package com.frontieraudio.heartbeat.transcription

import android.util.Log
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class CartesiaHttpClient(
    private val config: TranscriptionConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val objectMapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val transcriptionResultsInternal = MutableSharedFlow<TranscriptionResult>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    
    val transcriptionResults: SharedFlow<TranscriptionResult> = transcriptionResultsInternal.asSharedFlow()
    
    fun transcribeSegment(verifiedSegment: VerifiedSpeechSegment, locationData: LocationData? = null) {
        scope.launch {
            val processingTimeMs = measureTimeMillis {
                try {
                    Log.i(TAG, "Starting transcription for segment ${verifiedSegment.segment.id}")
                    Log.d(TAG, "Segment details: ${verifiedSegment.segment.durationMillis}ms, ${verifiedSegment.segment.sampleRateHz}Hz")
                    
                    // Convert audio samples to WAV format for Cartesia
                    val wavBytes = convertToWav(verifiedSegment.segment.samples, verifiedSegment.segment.sampleRateHz)
                    Log.i(TAG, "Converted audio to WAV: ${wavBytes.size} bytes")
                    
                    // Create multipart form request
                    val request = createTranscriptionRequest(wavBytes, verifiedSegment.segment.sampleRateHz)
                    Log.d(TAG, "Created HTTP request to: ${request.url}")
                    Log.d(TAG, "Request headers: ${request.headers}")
                    
                    Log.i(TAG, "Sending request to Cartesia API...")
                    val response = client.newCall(request).execute()
                    
                    Log.i(TAG, "Cartesia API response: ${response.code} ${response.message}")
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "Response body length: ${responseBody?.length ?: 0}")
                        
                        if (!responseBody.isNullOrEmpty()) {
                            Log.d(TAG, "Response body preview: ${responseBody.take(200)}...")
                            val result = parseTranscriptionResponse(responseBody, verifiedSegment, locationData)
                            val emitted = transcriptionResultsInternal.emit(result)
                            Log.i(TAG, "Transcription successful: ${result.transcript}")
                            Log.d(TAG, "Result emitted: $emitted")
                        } else {
                            Log.w(TAG, "Empty response from Cartesia API")
                            emitErrorResult(verifiedSegment, "Empty response", locationData)
                        }
                    } else {
                        val errorBody = response.body?.string()
                        Log.e(TAG, "Cartesia API error: ${response.code} - $errorBody")
                        Log.e(TAG, "Response headers: ${response.headers}")
                        emitErrorResult(verifiedSegment, "API error: ${response.code}", locationData)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error transcribing segment ${verifiedSegment.segment.id}", e)
                    emitErrorResult(verifiedSegment, e.message ?: "Unknown error", locationData)
                }
            }
            
            Log.i(TAG, "Transcription processing completed in ${processingTimeMs}ms")
        }
    }
    
    private fun convertToWav(audioSamples: ShortArray, sampleRateHz: Int): ByteArray {
        val dataLength = audioSamples.size * 2 // 16-bit samples = 2 bytes each
        val totalLength = 36 + dataLength // WAV header + data
        
        return java.io.ByteArrayOutputStream().use { baos ->
            // WAV header
            baos.write("RIFF".toByteArray()) // ChunkID
            baos.write(intToByteArray(totalLength)) // ChunkSize
            baos.write("WAVE".toByteArray()) // Format
            baos.write("fmt ".toByteArray()) // Subchunk1ID
            baos.write(intToByteArray(16)) // Subchunk1Size (16 for PCM)
            baos.write(shortToByteArray(1)) // AudioFormat (1 for PCM)
            baos.write(shortToByteArray(1)) // NumChannels (1 for mono)
            baos.write(intToByteArray(sampleRateHz)) // SampleRate
            baos.write(intToByteArray(sampleRateHz * 2)) // ByteRate
            baos.write(shortToByteArray(2)) // BlockAlign
            baos.write(shortToByteArray(16)) // BitsPerSample
            baos.write("data".toByteArray()) // Subchunk2ID
            baos.write(intToByteArray(dataLength)) // Subchunk2Size
            
            // Audio data
            for (sample in audioSamples) {
                baos.write(byteArrayOf((sample.toInt() and 0xFF).toByte(), ((sample.toInt() shr 8) and 0xFF).toByte()))
            }
            
            baos.toByteArray()
        }
    }
    
    private fun createTranscriptionRequest(wavBytes: ByteArray, sampleRateHz: Int): Request {
        val boundary = "----CartesiaBoundary${System.currentTimeMillis()}"
        val mimeType = "multipart/form-data; boundary=$boundary"
        
        val bodyBuilder = StringBuilder()
        
        // Add file part
        bodyBuilder.append("--$boundary\r\n")
        bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        bodyBuilder.append("Content-Type: audio/wav\r\n\r\n")
        
        val headerBytes = bodyBuilder.toString().toByteArray()
        val footerBytes = "\r\n--$boundary--\r\n".toByteArray()
        
        // Add other form fields
        val fields = mutableMapOf(
            "model" to "ink-whisper",
            "language" to config.language
        )
        
        if (config.includeTimestamps) {
            fields["timestamp_granularities[]"] = "word"
        }
        
        // Build form data
        val formDataBuilder = StringBuilder()
        for ((key, value) in fields) {
            formDataBuilder.append("--$boundary\r\n")
            formDataBuilder.append("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
            formDataBuilder.append("$value\r\n")
        }
        
        val fieldsBytes = formDataBuilder.toString().toByteArray()
        val totalBody = headerBytes + fieldsBytes + wavBytes + footerBytes
        
        return Request.Builder()
            .url("https://api.cartesia.ai/stt")
            .addHeader("X-API-Key", config.cartesiaApiKey)
            .addHeader("Content-Type", mimeType)
            .post(totalBody.toRequestBody(mimeType.toMediaType()))
            .build()
    }
    
    private fun parseTranscriptionResponse(
        responseJson: String, 
        segment: VerifiedSpeechSegment, 
        locationData: LocationData?
    ): TranscriptionResult {
        return try {
            val response: CartesiaTranscriptionResponse = objectMapper.readValue(responseJson)
            TranscriptionResult(
                segment = segment,
                transcript = response.text,
                confidence = null, // Cartesia doesn't provide confidence in this format
                isFinal = true,
                processingTimeMs = 0, // Already measured in transcribeSegment
                locationData = locationData
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing transcription response: $responseJson", e)
            TranscriptionResult(
                segment = segment,
                transcript = "[Parse Error: ${e.message}]",
                confidence = 0f,
                isFinal = true,
                processingTimeMs = 0,
                locationData = locationData
            )
        }
    }
    
    private fun emitErrorResult(segment: VerifiedSpeechSegment, error: String, locationData: LocationData?) {
        val result = TranscriptionResult(
            segment = segment,
            transcript = "[Error: $error]",
            confidence = 0f,
            isFinal = true,
            processingTimeMs = 0,
            locationData = locationData
        )
        
        scope.launch {
            transcriptionResultsInternal.emit(result)
        }
    }
    
    // Utility functions for WAV file creation
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
    
    fun cleanup() {
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    
    companion object {
        private const val TAG = "CartesiaHttpClient"
    }
    
    // Simple time measurement function
    private inline fun measureTimeMillis(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}

data class CartesiaTranscriptionResponse(
    @JsonProperty("text") val text: String
)