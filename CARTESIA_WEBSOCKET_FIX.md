# Cartesia WebSocket STT Integration Fix

## Date: 2025-01-26

## Problem Summary

The app was successfully connecting to the Cartesia WebSocket STT endpoint, but immediately receiving an "invalid sample_rate" error (400) from the server, causing the connection to close and repeatedly reconnect.

### Error Symptoms
- ✅ Stage 1 (VAD): Working
- ✅ Stage 2 (Speaker Verification): Working - green card appearing
- ❌ Stage 3 (Transcription): Failing with "invalid sample_rate" error
- WebSocket connection opening successfully
- Immediate 400 error after connection
- No transcription text appearing in UI

### Log Evidence
```
✅ WebSocket connection opened successfully - ready to stream audio
Init message sent: {"model":"ink-whisper","language":"en","encoding":"pcm_s16le","sample_rate":"16000"}
❌ Cartesia error (400): invalid sample rate
WebSocket EOF after sending audio chunk
```

## Root Cause

After reviewing the official Cartesia API documentation at https://docs.cartesia.ai/api-reference/stt/stt, I discovered the issue:

**The `cartesia_version` parameter was being sent as a QUERY PARAMETER, but it should be a HEADER.**

### Incorrect Implementation (Before)
```kotlin
val urlWithAuth = "${config.cartesiaUrl}?api_key=${config.cartesiaApiKey}&cartesia_version=2025-04-16&model=ink-whisper&language=${config.language}&encoding=pcm_s16le&sample_rate=16000"

val request = Request.Builder()
    .url(urlWithAuth)
    .build()
```

### Correct Implementation (After)
```kotlin
val urlWithAuth = "${config.cartesiaUrl}?api_key=${config.cartesiaApiKey}&model=ink-whisper&language=${config.language}&encoding=pcm_s16le&sample_rate=16000"

val request = Request.Builder()
    .url(urlWithAuth)
    .addHeader("Cartesia-Version", "2025-04-16")
    .build()
```

## Cartesia API Specification

Based on the official documentation:

### WebSocket Endpoint
```
wss://api.cartesia.ai/stt/websocket
```

### Required Headers
- `Cartesia-Version`: "2025-04-16" (or latest version)
- `X-API-Key`: (optional, alternative to api_key query param)

### Required Query Parameters
- `model` (string): "ink-whisper"
- `language` (string): ISO-639-1 code, e.g., "en"
- `encoding` (string): "pcm_s16le" recommended
- `sample_rate` (string): "16000" recommended
- `api_key` (string): Your API key (can use header instead)

### Optional Query Parameters
- `min_volume` (string): Volume threshold for VAD (0.0-1.0)
- `max_silence_duration_secs` (string): Max silence before endpointing

### Protocol Flow
1. Connect to WebSocket with query params and headers
2. Send binary messages containing raw PCM audio chunks
3. Receive JSON text messages with transcription results
4. Send text command "finalize" to flush remaining audio
5. Send text command "done" to close session cleanly

### Message Formats

**Sending Audio:**
- Binary WebSocket message
- Raw PCM audio in specified encoding (pcm_s16le)
- Recommended: send in small chunks (e.g., 100ms intervals)

**Receiving Transcription:**
```json
{
  "type": "transcript",
  "is_final": false,
  "request_id": "58dfa4d4-91c5-410c-8529-6824c8f7aedc",
  "text": "How are you doing today?",
  "duration": 0.5,
  "language": "en",
  "words": [...]
}
```

**Receiving Acknowledgments:**
```json
{"type": "flush_done", "request_id": "..."}
{"type": "done", "request_id": "..."}
```

**Receiving Errors:**
```json
{
  "type": "error",
  "error": "error message",
  "request_id": "..."
}
```

## The Fix

**File Changed:** `app/src/main/java/com/frontieraudio/heartbeat/transcription/CartesiaWebSocketClient.kt`

**Changes Made:**
1. Removed `cartesia_version=2025-04-16` from query string
2. Added `.addHeader("Cartesia-Version", "2025-04-16")` to request builder
3. Updated log messages to reflect header usage

## Why This Fixes It

The Cartesia server was rejecting the connection because it received an unrecognized query parameter (`cartesia_version`). The "invalid sample_rate" error was likely a misleading error message - the server was actually rejecting the entire handshake due to the malformed request.

By moving `cartesia_version` to a header (where it belongs), the WebSocket handshake should now succeed, and the server will properly accept the `sample_rate` parameter.

## Testing Checklist

After this fix, you should observe:

- [ ] WebSocket connection opens successfully
- [ ] No "invalid sample_rate" error
- [ ] Audio chunks being sent successfully
- [ ] Transcription responses received from Cartesia
- [ ] Transcription text appearing in UI
- [ ] Stage 3 completing successfully

## Additional Notes

### Best Practices from Documentation
1. **Audio Format**: PCM 16-bit little-endian at 16kHz is recommended
2. **Chunk Size**: Send audio in small chunks (~100ms) for optimal latency
3. **Timeout**: Idle connections close after 20 seconds of inactivity
4. **Concurrency**: Dedicated limit for STT WebSocket connections
5. **Pricing**: 1 credit per 1 second of audio streamed

### Future Optimization Opportunities
1. Consider using `X-API-Key` header instead of query param for better security
2. Implement min_volume parameter for better VAD integration
3. Add max_silence_duration_secs for endpointing control
4. Handle word-level timestamps if needed for UI features

## References

- Official Cartesia STT Documentation: https://docs.cartesia.ai/api-reference/stt/stt
- OkHttp WebSocket Documentation: https://square.github.io/okhttp/
- ISO-639-1 Language Codes: https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes