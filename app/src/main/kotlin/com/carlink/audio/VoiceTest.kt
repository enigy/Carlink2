package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * [224] Parked voice test (Settings → Logs → Voice test): records the driver counting aloud on each
 * mic path a call could use, then plays every take back through the speakers, so the driver hears
 * what each path picks up.
 *  - "Call mic": VOICE_COMMUNICATION with AEC/NS/AGC in MODE_IN_COMMUNICATION — what calls send
 *    today ([MicrophoneCaptureManager]).
 *  - "Raw mic": AudioSource.MIC, no effects.
 *  - "Voice_Uplink": GM's processed uplink bus (what GM's own CarPlay records), when listed. The
 *    echo test heard no echo on it, which an echo-free mic and an empty bus both explain; a voice on
 *    it tells them apart.
 *
 * Logs each take's voice level (95th percentile of 20 ms frames) and quiet level (10th) as
 * [VOICE_TEST] lines. Takes are kept in memory only, for replay from the card.
 */
internal class VoiceTest(
    private val context: Context,
    private val blockedReason: () -> String?,
    private val log: (String) -> Unit,
) {
    private data class Input(
        val label: String,
        val source: Int,
        val appEffects: Boolean,
        val commMode: Boolean,
        val device: AudioDeviceInfo? = null,
    )

    class Take(
        val label: String,
        val pcm: ShortArray = ShortArray(0),
        val voiceDbfs: Double? = null,
        val quietDbfs: Double? = null,
        val error: String? = null,
        val details: String = "",
    ) {
        val rangeDb: Double? get() = if (voiceDbfs != null && quietDbfs != null) voiceDbfs - quietDbfs else null
    }

    /** [takes] in recording order; [notes] are messages and the verdict, for the card. */
    class Outcome(
        val takes: List<Take>,
        val notes: List<String>,
    )

    /** Record every input, then play the takes back (~45 s). Blocking: call off the main thread. */
    @Suppress("ReturnCount", "LongMethod", "NestedBlockDepth")
    fun run(progress: (String) -> Unit): Outcome {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Outcome(emptyList(), listOf("Microphone permission isn't granted."))
        }
        blockedReason()?.let { return Outcome(emptyList(), listOf("Can't run while $it.")) }
        val am = context.getSystemService(AudioManager::class.java) ?: return Outcome(emptyList(), listOf("No AudioManager."))
        if (!AudioTestLock.tryLock()) return Outcome(emptyList(), listOf("Another audio test is running."))
        try {
            val inputs =
                listOfNotNull(
                    Input("Call mic", MediaRecorder.AudioSource.VOICE_COMMUNICATION, appEffects = true, commMode = true),
                    Input("Raw mic", MediaRecorder.AudioSource.MIC, appEffects = false, commMode = false),
                    AudioRouteInfo.uplinkBus(am)?.let {
                        Input("Voice_Uplink", MediaRecorder.AudioSource.VOICE_COMMUNICATION, false, true, it)
                    },
                )
            val originalMode = am.mode
            val focus =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(voiceCallAttributes())
                    .build()
            val focusResult = am.requestAudioFocus(focus)
            log("[VOICE_TEST] start: ${inputs.joinToString { it.label }} mode=$originalMode focus=$focusResult")

            val takes = mutableListOf<Take>()
            try {
                for ((i, input) in inputs.withIndex()) {
                    val step = "${i + 1} of ${inputs.size}: ${input.label}"
                    progress("Get ready to talk ($step)…")
                    Thread.sleep(READY_MS)
                    blockedReason()?.let { return Outcome(takes, listOf("Stopped: $it.")) }
                    progress("Talk now: count slowly to five ($step)")
                    val take =
                        try {
                            record(input, am)
                        } catch (e: Exception) {
                            Take(input.label, error = "${e.javaClass.simpleName}: ${e.message}")
                        }
                    log(logLine(take))
                    takes += take
                }
            } finally {
                am.abandonAudioFocusRequest(focus)
                // A call that started meanwhile owns the mode now (CarlinkManager restores it later).
                if (blockedReason() == null) am.mode = originalMode
            }

            val verdict = verdict(takes)
            verdict?.let { log("[VOICE_TEST] verdict: $it") }
            val playable = takes.filter { it.pcm.isNotEmpty() }
            for ((i, take) in playable.withIndex()) {
                if (blockedReason() != null) break
                progress("Playing back ${i + 1} of ${playable.size}: ${take.label}")
                playTake(take, am)
                Thread.sleep(BETWEEN_MS)
            }
            return Outcome(takes, listOfNotNull(verdict))
        } finally {
            AudioTestLock.unlock()
        }
    }

    /** Replay one take through the speakers. Blocking: call off the main thread. */
    fun play(take: Take): String? {
        if (take.pcm.isEmpty()) return "Nothing recorded."
        blockedReason()?.let { return "Can't play while $it." }
        val am = context.getSystemService(AudioManager::class.java) ?: return "No AudioManager."
        if (!AudioTestLock.tryLock()) return "Another audio test is running."
        try {
            playTake(take, am)
            return null
        } finally {
            AudioTestLock.unlock()
        }
    }

    @Suppress("ReturnCount")
    private fun record(
        input: Input,
        am: AudioManager,
    ): Take {
        am.mode = if (input.commMode) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        val recMin = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (recMin <= 0) return Take(input.label, error = "no buffer size ($recMin)")
        var record: AudioRecord? = null
        val effects = mutableListOf<AudioEffect>()
        try {
            record = AudioRecord(input.source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMin * 3)
            if (record.state != AudioRecord.STATE_INITIALIZED) return Take(input.label, error = "AudioRecord didn't initialize")
            input.device?.let { record.setPreferredDevice(it) }
            val appFx = if (input.appEffects) attachVoiceEffects(record.audioSessionId, effects) else "none"
            val pcm = ShortArray(SR * TAKE_S)
            var filled = 0
            var details = ""
            val deadline = System.nanoTime() + (TAKE_S * 1000L + TIMEOUT_MARGIN_MS) * NANOS_PER_MS
            record.startRecording()
            while (filled < pcm.size && System.nanoTime() < deadline) {
                val n = record.read(pcm, filled, min(CHUNK, pcm.size - filled))
                if (n < 0) return Take(input.label, error = "AudioRecord read failed ($n)", details = details)
                filled += n
                if (details.isEmpty() && filled >= SR / DETAILS_AFTER_DIV) {
                    details =
                        "mode=${am.mode} in=${AudioRouteInfo.device(record.routedDevice)} appFx=[$appFx] " +
                        AudioRouteInfo.recording(am, record.audioSessionId)
                }
            }
            val x = EchoDsp.shortsToFloat(pcm, filled)
            val frames = (0 until filled / CHUNK).map { EchoDsp.rmsDbfs(x, it * CHUNK, CHUNK) }.sorted()
            if (frames.size < MIN_FRAMES) return Take(input.label, error = "recorded only ${filled * 1000 / SR} ms", details = details)
            return Take(
                input.label,
                pcm = pcm.copyOf(filled),
                voiceDbfs = frames[frames.size * VOICE_PCT / 100],
                quietDbfs = frames[frames.size * QUIET_PCT / 100],
                details = details,
            )
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    /** Play [take] as recorded (no gain added) through the media speakers, and wait for it to finish. */
    private fun playTake(
        take: Take,
        am: AudioManager,
    ) {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
        am.requestAudioFocus(focus)
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(minBuf * PLAY_BUFFER_MULTIPLIER)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        try {
            track.play()
            var written = 0
            while (written < take.pcm.size) {
                val n = track.write(take.pcm, written, take.pcm.size - written)
                if (n <= 0) break
                written += n
            }
            val deadline = System.nanoTime() + (take.pcm.size * 1000L / SR + TIMEOUT_MARGIN_MS) * NANOS_PER_MS
            while (track.playbackHeadPosition < written && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        } finally {
            runCatching { track.stop() }
            track.release()
            am.abandonAudioFocusRequest(focus)
        }
    }

    private fun logLine(t: Take): String =
        "[VOICE_TEST] ${t.label}: " +
            (t.error?.let { "failed: $it" } ?: "voice=${fmt(t.voiceDbfs)}dBFS quiet=${fmt(t.quietDbfs)}dBFS (${fmt(t.rangeDb)} dB above)") +
            " | ${t.details}"

    /** Did Voice_Uplink carry the voice the raw mic heard? */
    private fun verdict(takes: List<Take>): String? {
        val raw = takes.firstOrNull { it.label == "Raw mic" } ?: return null
        val bus = takes.firstOrNull { it.label == "Voice_Uplink" } ?: return null
        val rawRange = raw.rangeDb
        val busRange = bus.rangeDb
        return when {
            rawRange == null || busRange == null -> null
            rawRange < VOICE_RANGE_DB -> "The raw mic didn't hear you. Run it again and talk at \"Talk now\"."
            busRange >= VOICE_RANGE_DB ->
                "Voice_Uplink carries your voice (${fmt(bus.voiceDbfs)} dBFS; raw mic ${fmt(raw.voiceDbfs)} dBFS)."
            else -> "Voice_Uplink did not carry your voice (raw mic heard it ${fmt(rawRange)} dB above quiet)."
        }
    }

    private fun fmt(v: Double?): String = v?.let { "%.0f".format(it) } ?: "?"

    private companion object {
        const val SR = 16_000
        const val TAKE_S = 5
        const val CHUNK = 320 // 20 ms
        const val READY_MS = 1_500L
        const val BETWEEN_MS = 600L
        const val TIMEOUT_MARGIN_MS = 3_000L
        const val POLL_MS = 20L
        const val DETAILS_AFTER_DIV = 5 // describe the route once 200 ms are in
        const val VOICE_PCT = 95
        const val QUIET_PCT = 10
        const val VOICE_RANGE_DB = 15.0
        const val MIN_FRAMES = 50
        const val PLAY_BUFFER_MULTIPLIER = 4
        const val NANOS_PER_MS = 1_000_000L
    }
}
