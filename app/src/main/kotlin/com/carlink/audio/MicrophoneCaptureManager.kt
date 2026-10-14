package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone format configuration matching CPC200-CCPA protocol voice formats.
 */
data class MicFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT

    val bytesPerSample: Int
        get() = channelCount * 2 // 16-bit = 2 bytes per channel
}

/**
 * Predefined microphone formats from CPC200-CCPA protocol.
 *
 * The capture format is NOT static — it is selected dynamically from the `decodeType`
 * field of the adapter's `AUDIO_INPUT_CONFIG` command at session start. CarPlay/Siri
 * use 16kHz wideband; Android Auto phone calls switch to 8kHz HFP/SCO narrowband.
 * See `documents/reference/adapter/RE_Documention/03_Audio_Processing/microphone_processing.md`.
 */
object MicFormats {
    val PHONE_CALL = MicFormatConfig(8000, 1)  // decodeType=3: Android Auto phone calls (HFP/SCO narrowband). CarPlay phone calls use SIRI_VOICE
    val SIRI_VOICE = MicFormatConfig(16000, 1) // decodeType=5: Siri/voice assistant + CarPlay phone calls (iAP2 wideband)
    // Dead-code-in-practice: WebRTC AECM accepts only 8kHz/16kHz, and the firmware does
    // not forward upstream mic audio at these rates downstream. Listed for protocol
    // completeness; never use as the live mic format.
    val ENHANCED = MicFormatConfig(24000, 1)   // decodeType=6: Enhanced voice — firmware accepts format but drops/misroutes the audio
    val STEREO_VOICE = MicFormatConfig(16000, 2) // decodeType=7: Stereo voice — same; firmware does not forward stereo mic audio

    fun fromDecodeType(decodeType: Int): MicFormatConfig =
        when (decodeType) {
            3 -> PHONE_CALL
            5 -> SIRI_VOICE
            6 -> ENHANCED
            7 -> STEREO_VOICE
            else -> SIRI_VOICE // Default to 16kHz mono
        }
}

/**
 * MicrophoneCaptureManager - Handles microphone capture for CPC200-CCPA voice input.
 *
 * PURPOSE:
 * Captures microphone audio for Siri/voice assistant and phone calls, sending PCM data
 * to the CPC200-CCPA adapter via USB. Uses a ring buffer architecture matching the
 * audio output pipeline for consistent, stutter-free capture.
 *
 * ARCHITECTURE:
 * ```
 * MicCaptureThread (THREAD_PRIORITY_URGENT_AUDIO)
 *     │
 *     ├── AudioRecord.read() [blocks on hardware]
 *     │
 *     └── micBuffer.write() [non-blocking]
 *            │
 *            ▼
 *      MicRingBuffer (500ms)
 *            │
 *            ▼
 *      readChunk() [non-blocking, called by USB send thread]
 * ```
 *
 * KEY FEATURES:
 * - Ring buffer absorbs AudioRecord timing jitter and USB send variations
 * - Dedicated high-priority capture thread
 * - Non-blocking reads for USB thread
 * - VOICE_COMMUNICATION audio source — requests OS-level echo cancellation /
 *   noise suppression. Actual effect is HAL-dependent and not guaranteed
 *   (varies by device; on the GM IOK target it depends on the SST HAL config).
 *
 * THREAD SAFETY:
 * - Capture thread writes to ring buffer (single writer)
 * - USB thread reads from ring buffer (single reader)
 * - Start/stop/configure called from main thread
 *
 * Mic capture lifecycle + AudioRecord/encoder plumbing; function count is the surface of one unit.
 */
@Suppress("TooManyFunctions")
class MicrophoneCaptureManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {
    private var audioRecord: AudioRecord? = null
    private var micBuffer: AudioRingBuffer? = null
    private var currentFormat: MicFormatConfig? = null
    private var captureThread: MicCaptureThread? = null
    private val isRunning = AtomicBoolean(false)

    // Platform voice pre-processing effects, attached explicitly to the capture session (see
    // [attachVoiceEffects]). Held so they can be released with the AudioRecord.
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var startTime: Long = 0

    // @Volatile: written by capture thread, read by main thread in getStats()/stop().
    // Long requires volatile for JMM atomicity (JLS 17.7); Int for visibility.
    // Matches AudioRingBuffer's @Volatile counter pattern.
    @Volatile private var totalBytesCapture: Long = 0

    @Volatile private var overrunCount: Int = 0

    // 500ms buffer prevents overruns when main thread blocked
    private val bufferCapacityMs = 500
    private val captureChunkMs = 20

    // Pre-allocated read buffer — reused by readChunk() to avoid per-call allocation.
    // Initially sized for 16kHz mono 20ms (640B); grows on demand if readChunk requests more.
    // Safe to reuse: caller (sendMicrophoneData) consumes data synchronously before next tick.
    private var readBuffer = ByteArray(640)

    private val lock = Any()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Start capture. [decodeType] should come from the adapter's `AUDIO_INPUT_CONFIG`:
     *  - 3 = Android Auto phone call (8kHz HFP/SCO narrowband)
     *  - 5 = Siri / voice assistant / CarPlay phone call (16kHz wideband — default)
     *  - 6 = enhanced voice (24kHz mono): firmware accepts the format but does not
     *        forward the audio downstream — dead in practice.
     *  - 7 = stereo voice (16kHz stereo): same — firmware does not forward stereo mic.
     *
     * AudioRecord bring-up with guard-clause early-returns for each failure mode; returns reflect them.
     */
    @Suppress("LongMethod", "ReturnCount")
    fun start(decodeType: Int = 5): Boolean {
        synchronized(lock) {
            if (isRunning.get()) {
                log("[MIC] Already capturing")
                return true
            }

            if (!hasPermission()) {
                log("[MIC] ERROR: RECORD_AUDIO permission not granted")
                return false
            }

            val format = MicFormats.fromDecodeType(decodeType)

            try {
                val minBufferSize =
                    AudioRecord.getMinBufferSize(
                        format.sampleRate,
                        format.channelConfig,
                        format.encoding,
                    )

                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    log("[MIC] ERROR: Invalid buffer size for ${format.sampleRate}Hz")
                    return false
                }

                val recordBufferSize = minBufferSize * 3

                // VOICE_COMMUNICATION requests OS echo cancellation/noise suppression
                // (HAL-dependent — see class doc).
                audioRecord =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        format.sampleRate,
                        format.channelConfig,
                        format.encoding,
                        recordBufferSize,
                    )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    log("[MIC] ERROR: AudioRecord failed to initialize")
                    audioRecord?.release()
                    audioRecord = null
                    return false
                }

                // Explicitly enable echo cancellation / noise suppression / gain control on the
                // capture session. VOICE_COMMUNICATION alone did NOT reliably apply them on the GM
                // Intel SST HAL (far end heard speaker→mic echo + a distant, low-level voice).
                audioRecord?.audioSessionId?.let { attachVoiceEffects(it) }

                micBuffer =
                    AudioRingBuffer(
                        capacityMs = bufferCapacityMs,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                currentFormat = format
                startTime = System.currentTimeMillis()
                totalBytesCapture = 0
                overrunCount = 0

                audioRecord?.startRecording()
                isRunning.set(true)
                captureThread = MicCaptureThread(format).also { it.start() }

                log(
                    "[MIC] Capture started: ${format.sampleRate}Hz ${format.channelCount}ch " +
                        "buffer=${recordBufferSize}B",
                )
                return true
            } catch (e: SecurityException) {
                log("[MIC] ERROR: Permission denied: ${e.message}")
                // Release AudioRecord if it was created before the exception.
                // AudioRecord.release() is unconditionally safe per AOSP source.
                // Without cleanup, the leaked AudioRecord holds the Intel SST HAL
                // input stream, blocking all future mic capture (Siri/phone calls).
                releaseVoiceEffects()
                audioRecord?.release()
                audioRecord = null
                micBuffer = null
                return false
            } catch (e: IllegalArgumentException) {
                log("[MIC] ERROR: Invalid parameters: ${e.message}")
                releaseVoiceEffects()
                audioRecord?.release()
                audioRecord = null
                micBuffer = null
                return false
            } catch (e: IllegalStateException) {
                log("[MIC] ERROR: Invalid state: ${e.message}")
                releaseVoiceEffects()
                audioRecord?.release()
                audioRecord = null
                micBuffer = null
                return false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            // Guard covers both normal stop and post-error cleanup (isRunning already false).
            // After a fatal capture error, cleanupAfterCaptureError() sets isRunning=false and
            // releases audioRecord, but micBuffer/currentFormat/captureThread still need cleanup.
            if (!isRunning.getAndSet(false)) {
                // If the capture thread already cleared isRunning (fatal error path),
                // we still need to join the thread and clean up remaining state.
                if (captureThread != null) {
                    log("[MIC] Stopping capture (post-error cleanup)")
                } else {
                    return
                }
            } else {
                log("[MIC] Stopping capture")
            }

            captureThread?.interrupt()
            try {
                captureThread?.join(1000)
            } catch (_: InterruptedException) {
            }
            captureThread = null

            // Release the voice effects before the session they're attached to goes away.
            releaseVoiceEffects()

            // audioRecord may already be null if capture thread released it on fatal error.
            // AudioRecord.release() is safe to call; null-check prevents NPE.
            try {
                audioRecord?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    record.release()
                }
            } catch (e: IllegalStateException) {
                log("[MIC] ERROR: Stop failed: ${e.message}")
            }
            audioRecord = null

            micBuffer?.clear()
            micBuffer = null

            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            currentFormat = null
            log("[MIC] Capture stopped: duration=${durationMs}ms bytes=$totalBytesCapture overruns=$overrunCount")
        }
    }

    /** Read captured audio (non-blocking, USB send thread). Returns null if empty. */
    fun readChunk(maxBytes: Int = 1920): ByteArray? {
        val buffer = micBuffer ?: return null

        val available = buffer.availableForRead()
        if (available == 0) {
            return null
        }

        val toRead = minOf(available, maxBytes)
        // Grow pre-allocated buffer if needed (rare — only if maxBytes increases)
        if (readBuffer.size < toRead) {
            readBuffer = ByteArray(toRead)
        }
        val bytesRead = buffer.read(readBuffer, 0, toRead)

        if (bytesRead <= 0) return null

        // Common case: bytesRead == readBuffer.size → return pre-allocated buffer directly (zero alloc)
        // Rare case: partial read → copyOf to avoid sending stale bytes (caller uses array.size)
        return if (bytesRead == readBuffer.size) readBuffer else readBuffer.copyOf(bytesRead)
    }

    /** Get current decode type (3, 5, 6, or 7), or -1 if not capturing. */
    fun getCurrentDecodeType(): Int {
        val format = currentFormat ?: return -1
        return when {
            format.sampleRate == 8000 && format.channelCount == 1 -> 3
            format.sampleRate == 16000 && format.channelCount == 1 -> 5
            format.sampleRate == 24000 && format.channelCount == 1 -> 6
            format.sampleRate == 16000 && format.channelCount == 2 -> 7
            else -> 5 // defensive — unreachable, currentFormat is always one of the four MicFormats
        }
    }

    fun isCapturing(): Boolean =
        isRunning.get() &&
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun bufferLevelMs(): Int = micBuffer?.fillLevelMs() ?: 0

    /**
     * Live enabled-state of the attached voice effects, for the in-call diagnostic pulse
     * (see CarlinkManager's [CALL_AUDIO] watch).
     *
     * Read fresh from each AudioEffect rather than reported from the attach-time result: the
     * framework can disable an effect AFTER we enable it (audio-policy change, another session
     * claiming the HAL voice path, a mode transition). [193] logged only the attach-time status —
     * "enabled=true" — and that told us nothing about whether the effect was still running when
     * the far end heard echo. A silent flip to OFF mid-call looks exactly like a canceller that
     * "doesn't work", so it must be sampled live.
     */
    fun voiceEffectStates(): String =
        "aec=${effectState(echoCanceler)} ns=${effectState(noiseSuppressor)} agc=${effectState(gainControl)}"

    private fun effectState(effect: AudioEffect?): String =
        if (effect == null) {
            "none"
        } else {
            try {
                if (effect.enabled) "on" else "OFF"
            } catch (e: IllegalStateException) {
                // Effect released or its underlying engine is gone.
                "dead"
            }
        }

    fun getStats(): Map<String, Any> {
        synchronized(lock) {
            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0

            return mapOf(
                "isCapturing" to isRunning.get(),
                "format" to (currentFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "decodeType" to getCurrentDecodeType(),
                "durationSeconds" to durationMs / 1000.0,
                "totalBytesCaptured" to totalBytesCapture,
                "bufferLevelMs" to bufferLevelMs(),
                "bufferCapacityMs" to bufferCapacityMs,
                "overrunCount" to overrunCount,
                "bufferStats" to (micBuffer?.getStats() ?: emptyMap()),
            )
        }
    }

    fun release() {
        stop()
        log("[MIC] MicrophoneCaptureManager released")
    }

    private fun log(message: String) {
        logCallback.log("MIC", message)
    }

    /**
     * Attach and enable the platform voice pre-processing effects on the capture [sessionId].
     *
     * Relying on the VOICE_COMMUNICATION audio source to apply these implicitly is not reliable on
     * the gminfo37 Intel SST HAL, which surfaced as the far end hearing speaker→mic echo and a
     * distant, low-level voice. Creating the effects explicitly is the standard Android approach;
     * each is guarded by `isAvailable()` and every outcome is logged so the field logs reveal
     * exactly which effects the HAL actually provides (AEC is the one that clears the echo; AGC
     * lifts a far-field cabin mic and is also the one most likely to need disabling if it pumps
     * road noise). All failures are non-fatal — the mic still captures without the effect.
     */
    private fun attachVoiceEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(sessionId)
                if (aec != null) {
                    val status = aec.setEnabled(true)
                    echoCanceler = aec
                    log("[MIC] AEC attached: enabled=${aec.enabled} setEnabledStatus=$status")
                } else {
                    log("[MIC] AEC available but create() returned null")
                }
            } else {
                log("[MIC] AEC NOT available on this HAL")
            }
        } catch (e: Exception) {
            log("[MIC] AEC attach failed: ${e.message}")
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    val status = ns.setEnabled(true)
                    noiseSuppressor = ns
                    log("[MIC] NS attached: enabled=${ns.enabled} setEnabledStatus=$status")
                } else {
                    log("[MIC] NS available but create() returned null")
                }
            } else {
                log("[MIC] NS NOT available on this HAL")
            }
        } catch (e: Exception) {
            log("[MIC] NS attach failed: ${e.message}")
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(sessionId)
                if (agc != null) {
                    val status = agc.setEnabled(true)
                    gainControl = agc
                    log("[MIC] AGC attached: enabled=${agc.enabled} setEnabledStatus=$status")
                } else {
                    log("[MIC] AGC available but create() returned null")
                }
            } else {
                log("[MIC] AGC NOT available on this HAL")
            }
        } catch (e: Exception) {
            log("[MIC] AGC attach failed: ${e.message}")
        }
    }

    /** Release the voice pre-processing effects. Safe to call when none were attached. */
    private fun releaseVoiceEffects() {
        try { echoCanceler?.release() } catch (e: Exception) { log("[MIC] AEC release: ${e.message}") }
        try { noiseSuppressor?.release() } catch (e: Exception) { log("[MIC] NS release: ${e.message}") }
        try { gainControl?.release() } catch (e: Exception) { log("[MIC] AGC release: ${e.message}") }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    /**
     * Release AudioRecord from capture thread after fatal read() error.
     *
     * Called WITHOUT holding [lock] to avoid deadlock (stop() holds lock during join()).
     * AudioRecord.release() is thread-safe per AOSP — it acquires its own internal lock
     * (mLock in native AudioRecord.cpp) and is safe to call from any thread.
     * Setting audioRecord to null prevents stop() from double-releasing.
     */
    private fun cleanupAfterCaptureError() {
        isRunning.set(false)
        releaseVoiceEffects()
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("CARLINK", "[MIC] Release during error cleanup: ${e.message}")
        }
        audioRecord = null
    }

    /** Capture thread (URGENT_AUDIO priority). Reads AudioRecord, writes to ring buffer. */
    private inner class MicCaptureThread(
        private val format: MicFormatConfig,
    ) : Thread("MicCapture") {
        private val chunkSize = (format.sampleRate * format.bytesPerSample * captureChunkMs) / 1000
        private val tempBuffer = ByteArray(chunkSize)

        // Real-time mic read loop; loop jumps/nesting are the capture/encode pacing logic.
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[MIC] Capture thread started with URGENT_AUDIO priority, chunk=${chunkSize}B")

            val record = audioRecord ?: return
            val buffer = micBuffer ?: return
            var fatalError = false

            while (isRunning.get() && !isInterrupted) {
                try {
                    val bytesRead = record.read(tempBuffer, 0, chunkSize)

                    when {
                        bytesRead > 0 -> {
                            val bytesWritten = buffer.write(tempBuffer, 0, bytesRead)
                            totalBytesCapture += bytesWritten

                            if (bytesWritten < bytesRead) {
                                overrunCount++
                                log("[MIC] Buffer overrun: wrote $bytesWritten of $bytesRead bytes")
                            }
                        }

                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                            log("[MIC] ERROR: Invalid operation")
                            fatalError = true
                            break
                        }

                        bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                            log("[MIC] ERROR: Bad value")
                            fatalError = true
                            break
                        }

                        bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                            log("[MIC] ERROR: AudioRecord dead")
                            fatalError = true
                            break
                        }

                        bytesRead == AudioRecord.ERROR -> {
                            log("[MIC] ERROR: Generic error")
                            fatalError = true
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("[MIC] Capture thread error: ${e.message}")
                }
            }

            // On fatal error: release AudioRecord and clear isRunning so isCapturing()
            // returns false and callers (USB send thread) see the state change immediately.
            // Must NOT acquire lock — stop() holds lock during join(), would deadlock.
            if (fatalError) {
                log("[MIC] Capture thread exiting due to fatal error, releasing AudioRecord")
                cleanupAfterCaptureError()
            }

            log("[MIC] Capture thread stopped, total captured: ${totalBytesCapture}B")
        }
    }
}
