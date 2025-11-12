<!-- 279c4a6e-d12b-4b72-975e-16bfc3bc6429 8ea52bf3-d306-4953-ac77-b6b7219a2749 -->
# Improve Eagle Speaker Enrollment Quality

## Problem Analysis

Current enrollment accepts all segments regardless of quality feedback, leading to low similarity scores (0.0-0.119) during verification. Eagle provides quality feedback (`AUDIO_TOO_SHORT`, `QUALITY_ISSUE`, `NO_VOICE_FOUND`, `UNKNOWN_SPEAKER`) that we're displaying but not acting upon.

## Root Cause

**Enrollment segments (800ms min) vs Verification segments (0ms min)**: Verification accepts very short segments that may not have enough audio data for accurate comparison. Additionally, enrollment may be capturing poor-quality audio that degrades the profile.

## Proposed Changes

### 1. Reject Poor Quality Enrollment Segments

**File**: `app/src/main/java/com/frontieraudio/heartbeat/EnrollmentActivity.kt`

In the segment callback (line 158-171), add quality filtering:

```kotlin
) { segment ->
    try {
        val result = profiler.enroll(segment.samples)
        
        // Only count samples if quality is acceptable
        val isGoodQuality = result.feedback == EagleProfilerEnrollFeedback.AUDIO_OK
        
        if (isGoodQuality) {
            samplesCaptured += segment.samples.size
        } else {
            Log.d(TAG, "Rejected enrollment segment due to feedback: ${result.feedback}")
        }
        
        withContext(Dispatchers.Main) {
            handleEnrollProgress(result.percentage, result.feedback, isGoodQuality)
        }
    } catch (e: EagleException) {
        Log.w(TAG, "Enrollment frame rejected\n${e.toDetailedString()}", e)
        withContext(Dispatchers.Main) {
            statusText.text = getString(R.string.enrollment_frame_error, e.message ?: "")
        }
    }
}
```

**Note**: Eagle's internal enrollment percentage already handles quality - it only advances when segments are good. We're just making the feedback clearer to users.

### 2. Increase Minimum Enrollment Segment Duration

**File**: `app/src/main/java/com/frontieraudio/heartbeat/EnrollmentActivity.kt` (line 390)

Change from 800ms to 1200ms for better quality:

```kotlin
private const val ENROLLMENT_MIN_SEGMENT_MS = 1200  // Increased from 800ms
```

**Rationale**: Longer segments provide more audio data for Eagle to analyze, improving profile quality.

### 3. Match Verification Segment Requirements to Enrollment

**File**: `app/src/main/java/com/frontieraudio/heartbeat/HeartbeatService.kt` (line 513)

Change minimum segment duration to match enrollment quality:

```kotlin
private const val MIN_SEGMENT_DURATION_MS = 1200  // Match enrollment requirements
```

**Rationale**: Verification should use similar-length segments as enrollment for consistent comparison. Currently verification accepts 0ms (any length), which may include very short, low-quality segments.

### 4. Enhance User Feedback for Quality Issues

**File**: `app/src/main/java/com/frontieraudio/heartbeat/EnrollmentActivity.kt` (line 324)

Update `handleEnrollProgress` signature and improve feedback:

```kotlin
private fun handleEnrollProgress(
    percentage: Float, 
    feedback: EagleProfilerEnrollFeedback,
    wasAccepted: Boolean = true
) {
    updateProgress(percentage)
    
    val statusMessage = when (feedback) {
        EagleProfilerEnrollFeedback.AUDIO_OK -> getString(R.string.enrollment_feedback_audio_ok)
        EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT -> getString(R.string.enrollment_feedback_too_short)
        EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER -> getString(R.string.enrollment_feedback_unknown_speaker)
        EagleProfilerEnrollFeedback.NO_VOICE_FOUND -> getString(R.string.enrollment_feedback_no_voice)
        EagleProfilerEnrollFeedback.QUALITY_ISSUE -> getString(R.string.enrollment_feedback_quality_issue)
    }
    
    // Show warning indicator for poor quality
    val prefix = if (wasAccepted) "" else "⚠️ "
    statusText.text = getString(R.string.enrollment_progress_status, prefix + statusMessage, percentage.toInt())
    
    if (percentage >= 100f) {
        completionRequested = true
        Log.d(TAG, "Enrollment reached 100%, stopping recording to begin export...")
        statusText.text = getString(R.string.enrollment_processing)
        primaryButton.isEnabled = false
        cancelButton.isEnabled = false
        stopRecording()
    }
}
```

### 5. Optional: Lower Verification Threshold (Conservative Approach)

**File**: `app/src/main/java/com/frontieraudio/heartbeat/speaker/SpeakerVerificationConfig.kt` (line 11)

If similarity scores remain low after quality improvements, consider lowering threshold:

```kotlin
const val DEFAULT_MATCH_THRESHOLD = 0.75f  // Lowered from 0.85f
```

**Recommendation**: Only do this AFTER testing the quality improvements above. Better enrollment quality should naturally increase similarity scores.

## Testing Steps

1. Clear existing profile and re-enroll with new quality requirements
2. During enrollment, observe feedback messages - should see more "quality issue" warnings if audio is poor
3. Speak clearly and naturally for longer durations
4. After enrollment, test verification by speaking
5. Check logs for similarity scores - should be significantly higher (>0.5) for enrolled speaker
6. If scores are still low (<0.5), consider lowering threshold to 0.75

## Expected Outcomes

- Higher quality enrollment profiles
- Similarity scores >0.5 for enrolled speakers (vs current 0.0-0.119)
- More consistent verification results
- Better user feedback during enrollment about audio quality

### To-dos

- [x] Require 15–20s voiced time before export in EnrollmentActivity
- [x] Add quality tips UI based on EagleProfilerEnrollFeedback
- [ ] Increase verification MIN_SEGMENT_DURATION_MS to 2000 ms
- [ ] Trim first 250 ms and use trimmed mean on frame scores
- [ ] Set default match threshold to 0.6 with config slider
- [ ] Detect/warn if OS audio effects (AGC/NR/voice-changer) active
- [ ] Log RMS, clipping %, speech ratio per segment; surface warnings
- [ ] Implement impostor baseline capture and auto-threshold suggestion