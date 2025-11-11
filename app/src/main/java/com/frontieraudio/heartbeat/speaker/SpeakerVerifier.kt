package com.frontieraudio.heartbeat.speaker

import ai.picovoice.eagle.Eagle
import ai.picovoice.eagle.EagleException
import ai.picovoice.eagle.EagleProfile
import android.content.Context
import android.util.Log
import com.frontieraudio.heartbeat.BuildConfig
import com.frontieraudio.heartbeat.SpeechSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SpeakerVerifier(context: Context) {

    private val applicationContext = context.applicationContext
    private val recognizerMutex = Mutex()

    private var eagle: Eagle? = null
    private var eagleProfile: EagleProfile? = null
    private var activeProfileId: String? = null
    private var frameLength: Int = 0
    private var sampleRateHz: Int = 0

    suspend fun verify(
        segment: SpeechSegment,
        profile: VoiceProfile,
        threshold: Float
    ): SpeakerVerificationResult = withContext(Dispatchers.Default) {
        try {
            val (similarity, scores) = processSegment(segment, profile)
            if (similarity.isNaN()) {
                SpeakerVerificationResult.Error(null)
            } else if (similarity >= threshold) {
                SpeakerVerificationResult.Match(similarity, scores)
            } else {
                SpeakerVerificationResult.NoMatch(similarity, scores)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to verify speaker", t)
            SpeakerVerificationResult.Error(t)
        }
    }

    private suspend fun processSegment(
        segment: SpeechSegment,
        profile: VoiceProfile
    ): Pair<Float, FloatArray> = recognizerMutex.withLock {
        val recognizer = ensureRecognizer(profile)
        if (segment.sampleRateHz != sampleRateHz) {
            Log.w(TAG, "Segment sample rate ${segment.sampleRateHz} does not match Eagle requirement $sampleRateHz; skipping segment")
            return 0f to floatArrayOf()
        }
        try {
            recognizer.reset()
        } catch (e: EagleException) {
            Log.w(TAG, "Failed to reset Eagle recognizer", e)
        }
        val localFrameLength = frameLength.takeIf { it > 0 } ?: return 0f to floatArrayOf()
        if (segment.samples.size < localFrameLength) {
            return 0f to floatArrayOf()
        }
        val buffer = ShortArray(localFrameLength)
        var index = 0
        var framesProcessed = 0
        var scoreSum = 0f
        val scores = mutableListOf<Float>()
        while (index + localFrameLength <= segment.samples.size) {
            segment.samples.copyInto(buffer, 0, index, index + localFrameLength)
            val frameScores = try {
                recognizer.process(buffer)
            } catch (e: EagleException) {
                Log.e(TAG, "Eagle processing failed", e)
                break
            }
            val score = frameScores.firstOrNull() ?: 0f
            framesProcessed++
            scoreSum += score
            scores.add(score)
            index += localFrameLength
        }
        if (framesProcessed == 0) {
            return 0f to floatArrayOf()
        }
        val similarity = scoreSum / framesProcessed
        return similarity to scores.toFloatArray()
    }

    private suspend fun ensureRecognizer(profile: VoiceProfile): Eagle {
        val accessKey = requireAccessKey()
        if (profile.profileBytes.isEmpty()) {
            throw IllegalArgumentException("Voice profile payload is empty")
        }
        return if (activeProfileId == profile.id && eagle != null) {
            eagle!!
        } else {
            eagle?.delete()
            eagleProfile?.delete()

            val newProfile = EagleProfile(profile.profileBytes)
            val newRecognizer = Eagle.Builder()
                .setAccessKey(accessKey)
                .setSpeakerProfile(newProfile)
                .build(applicationContext)

            eagle = newRecognizer
            eagleProfile = newProfile
            activeProfileId = profile.id
            frameLength = newRecognizer.frameLength
            sampleRateHz = newRecognizer.sampleRate
            Log.i(TAG, "Initialized Eagle recognizer frameLength=$frameLength sampleRate=$sampleRateHz")
            newRecognizer
        }
    }

    private fun requireAccessKey(): String {
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        require(accessKey.isNotBlank()) { "PICOVOICE_ACCESS_KEY is empty. Provide a valid Picovoice AccessKey." }
        return accessKey
    }

    fun close() {
        runBlocking {
            recognizerMutex.withLock {
                eagle?.delete()
                eagle = null
                eagleProfile?.delete()
                eagleProfile = null
                activeProfileId = null
            }
        }
    }

    companion object {
        private const val TAG = "SpeakerVerifier"
    }
}
