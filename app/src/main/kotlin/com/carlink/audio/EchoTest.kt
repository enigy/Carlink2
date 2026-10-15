package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import com.carlink.platform.AudioConfig
import com.carlink.platform.PlatformDetector
import kotlin.math.abs
import kotlin.math.min

/**
 * Parked echo test (Settings → Logs → Echo test): how much of the call audio the mic picks back
 * up, and how late, without a phone call.
 *
 * Plays test sweeps on a track set up like the call track (16 kHz mono, USAGE_VOICE_COMMUNICATION,
 * the platform's buffer size and performance mode) and records them back several ways:
 *  - "Call mic": VOICE_COMMUNICATION with AEC/NS/AGC attached, in MODE_IN_COMMUNICATION — what a
 *    call uses today ([MicrophoneCaptureManager], [193]/[194]).
 *  - "Call mic, normal mode": the same in MODE_NORMAL, to see whether the mode matters ([194]).
 *  - "Raw mic": AudioSource.MIC, no effects — nothing cancelling at all.
 *  - "GM uplink bus": VOICE_COMMUNICATION from the Voice_Uplink input bus (mic_for_vc, the processed
 *    uplink GM's own CarPlay records), if the platform lists it.
 *
 * Each run plays a 2 s up-sweep, then a 1 s down-sweep. The two barely correlate, so the first
 * trains any echo canceller and the second measures what's left once it has adapted:
 *  - delay: handing the up-sweep to AudioTrack to reading its echo from AudioRecord — the part of
 *    the call round trip below the app — split by the track/record timestamps where the HAL gives them;
 *  - early / late: the echo's level relative to what was played, during the up-sweep and the
 *    down-sweep. A working canceller makes "late" much lower on the call mic than on the raw mic.
 *
 * Results go to the log ([ECHO_TEST], MIC tag) and back to the caller as summary lines.
 */
internal class EchoTest(
    private val context: Context,
    private val blockedReason: () -> String?,
    private val log: (String) -> Unit,
) {
    private data class Variant(
        val label: String,
        val source: Int,
        val appEffects: Boolean,
        val commMode: Boolean,
        val device: AudioDeviceInfo? = null,
    )

    @Suppress("LongParameterList") // one field per logged number
    private class Result(
        val variant: Variant,
        val error: String? = null,
        val found: Boolean = false,
        val delayMs: Double? = null,
        val outMs: Double? = null,
        val cabinMs: Double? = null,
        val inMs: Double? = null,
        val earlyDb: Double? = null,
        val earlyPnr: Double? = null,
        val lateDb: Double? = null,
        val latePnr: Double? = null,
        val noiseDbfs: Double? = null,
        val details: String = "",
    )

    /** Hand-over times of a growing stream: frames [end of previous entry, end) at time. */
    private class Stamps {
        private var ends = LongArray(STAMPS_INITIAL)
        private var times = LongArray(STAMPS_INITIAL)
        private var count = 0

        fun add(
            end: Long,
            ns: Long,
        ) {
            if (count == ends.size) {
                ends = ends.copyOf(count * 2)
                times = times.copyOf(count * 2)
            }
            ends[count] = end
            times[count] = ns
            count++
        }

        fun timeOf(frame: Long): Long? {
            for (i in 0 until count) if (ends[i] > frame) return times[i]
            return null
        }
    }

    private class Reader(
        private val record: AudioRecord,
        val out: ShortArray,
        val stamps: Stamps,
        private val deadlineNs: Long,
    ) : Thread("EchoTestRec") {
        @Volatile var filled = 0

        @Volatile var error = 0

        @Volatile var running = true

        override fun run() {
            while (running && filled < out.size && System.nanoTime() < deadlineNs) {
                val n = record.read(out, filled, min(CHUNK, out.size - filled), AudioRecord.READ_NON_BLOCKING)
                when {
                    n > 0 -> {
                        filled += n
                        stamps.add(filled.toLong(), System.nanoTime())
                    }
                    n == 0 -> sleep(POLL_MS)
                    else -> {
                        error = n
                        return
                    }
                }
            }
        }
    }

    /** Runs every variant (~25 s). Blocking: call off the main thread. Returns lines for the UI. */
    @Suppress("ReturnCount", "LongMethod")
    fun run(progress: (String) -> Unit): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return listOf("Microphone permission isn't granted.")
        }
        blockedReason()?.let { return listOf("Can't run while $it.") }
        val am = context.getSystemService(AudioManager::class.java) ?: return listOf("No AudioManager.")
        val config = AudioConfig.forPlatform(PlatformDetector.detect(context))
        val originalMode = am.mode

        val train = EchoDsp.sweep(SR, TRAIN_S, F_LO, F_HI, AMPLITUDE)
        val meas = EchoDsp.sweep(SR, MEAS_S, F_HI, F_LO, AMPLITUDE)
        val lead = (SR * LEAD_S).toInt()
        val gap = (SR * GAP_S).toInt()
        val playF = FloatArray(lead + train.size + gap + meas.size + (SR * TAIL_S).toInt())
        train.copyInto(playF, lead)
        meas.copyInto(playF, lead + train.size + gap)
        val play = EchoDsp.floatToShorts(playF)

        val bus = AudioRouteInfo.uplinkBus(am)
        val variants =
            listOfNotNull(
                Variant("Call mic", MediaRecorder.AudioSource.VOICE_COMMUNICATION, appEffects = true, commMode = true),
                Variant("Call mic, normal mode", MediaRecorder.AudioSource.VOICE_COMMUNICATION, appEffects = true, commMode = false),
                Variant("Raw mic", MediaRecorder.AudioSource.MIC, appEffects = false, commMode = false),
                bus?.let {
                    Variant("GM uplink bus ${it.address}", MediaRecorder.AudioSource.VOICE_COMMUNICATION, false, true, it)
                },
            )

        val focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(voiceCallAttributes())
                .build()
        if (!AudioTestLock.tryLock()) return listOf("Another audio test is running.")
        val focusResult = am.requestAudioFocus(focus)
        log(
            "[ECHO_TEST] start: ${variants.size} variants, sr=$SR trackBuf=minBuf*${config.bufferMultiplier} " +
                "perfMode=${config.performanceMode} mode=$originalMode focus=$focusResult",
        )
        log("[ECHO_TEST] inputs: ${AudioRouteInfo.inputs(am)}")
        log("[ECHO_TEST] voice effects on this platform: ${AudioRouteInfo.voiceEffects()}")

        val results = mutableListOf<Result>()
        try {
            variants.forEachIndexed { i, v ->
                progress("Testing ${i + 1} of ${variants.size}: ${v.label}…")
                val r =
                    try {
                        runVariant(v, am, config, play, train, meas, lead, gap)
                    } catch (e: Exception) {
                        Result(v, error = "${e.javaClass.simpleName}: ${e.message}")
                    }
                log(logLine(r))
                results += r
            }
        } finally {
            am.abandonAudioFocusRequest(focus)
            // A call that started meanwhile owns the mode now (CarlinkManager restores it later).
            if (blockedReason() == null) am.mode = originalMode
            AudioTestLock.unlock()
        }
        val verdict = verdict(results)
        verdict?.let { log("[ECHO_TEST] verdict: $it") }
        return results.map { summaryLine(it) } + listOfNotNull(verdict)
    }

    @Suppress("LongMethod", "LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
    private fun runVariant(
        v: Variant,
        am: AudioManager,
        config: AudioConfig,
        play: ShortArray,
        train: FloatArray,
        meas: FloatArray,
        lead: Int,
        gap: Int,
    ): Result {
        am.mode = if (v.commMode) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        val mode = am.mode
        val recMin = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val trackMin = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (recMin <= 0 || trackMin <= 0) return Result(v, error = "no buffer size (rec=$recMin track=$trackMin)")

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var reader: Reader? = null
        val effects = mutableListOf<AudioEffect>()
        try {
            record = AudioRecord(v.source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMin * 3)
            if (record.state != AudioRecord.STATE_INITIALIZED) return Result(v, error = "AudioRecord didn't initialize")
            v.device?.let { record.setPreferredDevice(it) }
            val appFx = if (v.appEffects) attachVoiceEffects(record.audioSessionId, effects) else "none"
            track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(voiceCallAttributes())
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(trackMin * config.bufferMultiplier)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(config.performanceMode)
                    .build()

            val deadline = System.nanoTime() + VARIANT_TIMEOUT_MS * NANOS_PER_MS
            val rd = Reader(record, ShortArray((SR * RECORD_S).toInt()), Stamps(), deadline)
            reader = rd
            record.startRecording()
            rd.start()
            Thread.sleep(SETTLE_MS)

            // Prime the track like the call path does (pre-fill, then play), then keep it fed.
            val writes = Stamps()
            var written = 0
            var details = ""
            val trainEnd = lead + train.size
            while (written < play.size && System.nanoTime() < deadline) {
                val n = track.write(play, written, min(CHUNK, play.size - written), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) return Result(v, error = "track write failed ($n)")
                if (n > 0) {
                    written += n
                    writes.add(written.toLong(), System.nanoTime())
                    continue
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() else Thread.sleep(POLL_MS)
                if (details.isEmpty() && written > trainEnd) {
                    details =
                        "mode=$mode out=${AudioRouteInfo.device(track.routedDevice)} " +
                        "in=${AudioRouteInfo.device(record.routedDevice)} appFx=[$appFx] " +
                        AudioRouteInfo.recording(am, record.audioSessionId)
                }
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            val trackTs = AudioTimestamp().takeIf { track.getTimestamp(it) }
            rd.join(VARIANT_TIMEOUT_MS)
            rd.running = false
            val recTs =
                AudioTimestamp().takeIf { record.getTimestamp(it, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS }
            if (rd.error != 0) return Result(v, error = "AudioRecord read failed (${rd.error})", details = details)

            return analyze(v, rd, writes, trackTs, recTs, train, meas, lead, gap, details)
        } finally {
            reader?.running = false
            reader?.join(POLL_MS * 4)
            effects.forEach { runCatching { it.release() } }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    @Suppress("LongParameterList")
    private fun analyze(
        v: Variant,
        rd: Reader,
        writes: Stamps,
        trackTs: AudioTimestamp?,
        recTs: AudioTimestamp?,
        train: FloatArray,
        meas: FloatArray,
        lead: Int,
        gap: Int,
        details: String,
    ): Result {
        val captured = rd.filled
        if (captured < train.size + meas.size + SR) {
            return Result(v, error = "recorded only ${captured * 1000 / SR} ms", details = details)
        }
        val x = EchoDsp.shortsToFloat(rd.out, captured)
        val noise = EchoDsp.rmsDbfs(x, 0, SR * NOISE_MS / 1000)

        val cT = EchoDsp.crossCorrelate(x, train, x.size - train.size)
        val pT = EchoDsp.peakAbs(cT)
        val earlyPnr = EchoDsp.peakToNoiseDb(cT, pT.index, SR * EXCLUDE_MS / 1000)
        val earlyDb = EchoDsp.db(abs(pT.value) / EchoDsp.energy(train))
        val found = earlyPnr >= FOUND_PNR_DB

        // The down-sweep's echo sits at the same delay, one up-sweep + gap later.
        val cM = EchoDsp.crossCorrelate(x, meas, x.size - meas.size)
        val predicted = pT.index + train.size + gap
        val pM = if (found) EchoDsp.peakAbs(cM, predicted - SEARCH, predicted + SEARCH + 1) else EchoDsp.peakAbs(cM)
        val latePnr = EchoDsp.peakToNoiseDb(cM, pM.index, SR * EXCLUDE_MS / 1000)
        val lateDb = EchoDsp.db(abs(pM.value) / EchoDsp.energy(meas))

        if (!found) {
            return Result(v, found = false, earlyDb = earlyDb, earlyPnr = earlyPnr, lateDb = lateDb, latePnr = latePnr, noiseDbfs = noise, details = details)
        }
        val p0 = lead.toLong()
        val r = pT.index.toLong()
        val tWrite = writes.timeOf(p0)
        val tRead = rd.stamps.timeOf(r)
        val present = trackTs?.let { it.nanoTime + (p0 - it.framePosition) * NANOS_PER_S / SR }
        val capture = recTs?.let { it.nanoTime + (r - it.framePosition) * NANOS_PER_S / SR }

        fun ms(
            a: Long?,
            b: Long?,
        ): Double? = if (a != null && b != null) (b - a) / NANOS_PER_MS.toDouble() else null
        return Result(
            v,
            found = true,
            delayMs = ms(tWrite, tRead),
            outMs = ms(tWrite, present),
            cabinMs = ms(present, capture),
            inMs = ms(capture, tRead),
            earlyDb = earlyDb,
            earlyPnr = earlyPnr,
            lateDb = lateDb,
            latePnr = latePnr,
            noiseDbfs = noise,
            details = details,
        )
    }


    private fun logLine(r: Result): String {
        val head = "[ECHO_TEST] ${r.variant.label}:"
        return when {
            r.error != null -> "$head failed: ${r.error} | ${r.details}"
            r.found ->
                "$head echo found, delay=${fmt(r.delayMs)}ms (track→speaker ${fmt(r.outMs)}, " +
                    "speaker→mic ${fmt(r.cabinMs)}, mic→app ${fmt(r.inMs)}) early=${fmt(r.earlyDb, 1)}dB " +
                    "late=${fmt(r.lateDb, 1)}dB (pnr ${fmt(r.earlyPnr)}/${fmt(r.latePnr)}dB) " +
                    "noise=${fmt(r.noiseDbfs)}dBFS | ${r.details}"
            else ->
                "$head no echo found (pnr ${fmt(r.earlyPnr)}dB, best early=${fmt(r.earlyDb, 1)}dB " +
                    "late=${fmt(r.lateDb, 1)}dB) noise=${fmt(r.noiseDbfs)}dBFS | ${r.details}"
        }
    }

    private fun summaryLine(r: Result): String =
        when {
            r.error != null -> "${r.variant.label}: failed (${r.error})"
            r.found ->
                "${r.variant.label}: echo ${fmt(r.earlyDb)} dB at first, ${lateText(r)} once adapted; " +
                    "delay ${fmt(r.delayMs)} ms"
            else -> "${r.variant.label}: no echo found"
        }

    private fun lateText(r: Result): String = if ((r.latePnr ?: 0.0) >= FOUND_PNR_DB) "${fmt(r.lateDb)} dB" else "below ${fmt(r.lateDb)} dB"

    /** Call mic vs raw mic, once adapted: how much echo the platform removes for our path. */
    private fun verdict(results: List<Result>): String? {
        val call = results.firstOrNull { it.variant.label == "Call mic" } ?: return null
        val raw = results.firstOrNull { it.variant.source == MediaRecorder.AudioSource.MIC } ?: return null
        if (!raw.found) return "Raw mic heard no echo either: turn the call volume up and run it again."
        val removed = (raw.lateDb ?: return null) - (call.lateDb ?: return null)
        val verdict =
            when {
                removed >= WORKING_DB -> "working"
                removed >= PARTIAL_DB -> "partial"
                else -> "not working"
            }
        return "Platform echo cancelling on the call mic: ${fmt(removed)} dB ($verdict)."
    }

    private fun fmt(
        v: Double?,
        decimals: Int = 0,
    ): String = v?.let { "%.${decimals}f".format(it) } ?: "?"

    private companion object {
        const val SR = 16_000
        const val F_LO = 150.0
        const val F_HI = 7_000.0
        const val AMPLITUDE = 0.3f
        const val LEAD_S = 0.4
        const val TRAIN_S = 2.0
        const val GAP_S = 0.1
        const val MEAS_S = 1.0
        const val TAIL_S = 1.5

        // Settle + lead + sweeps + up to 2.2 s of round trip.
        const val RECORD_S = 0.2 + LEAD_S + TRAIN_S + GAP_S + MEAS_S + 2.2
        const val SETTLE_MS = 200L
        const val NOISE_MS = 150
        const val CHUNK = 320
        const val POLL_MS = 5L
        const val VARIANT_TIMEOUT_MS = 12_000L
        const val EXCLUDE_MS = 20
        const val SEARCH = 80 // 5 ms either side of the predicted down-sweep echo
        const val FOUND_PNR_DB = 15.0
        const val WORKING_DB = 20.0
        const val PARTIAL_DB = 6.0
        const val STAMPS_INITIAL = 1024
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_S = 1_000_000_000L
    }
}
