package com.carlink.audio

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One direction of call audio as the app handed it on: samples in order, plus when each chunk
 * was handed over (System.nanoTime), so a sample index can be turned back into a time.
 *
 * Gaps longer than [gapMs] (the far end's silence suppression, a stalled mic) are filled with
 * zeros, so a window that spans a gap keeps its timing and still lines up with the other side.
 * Not thread-safe; [CallEchoMonitor] guards it.
 */
internal class EchoTimeline(
    private val sampleRate: Int,
    seconds: Int,
    private val gapMs: Int = 100,
) {
    private val capacity = sampleRate * seconds
    private val samples = FloatArray(capacity)

    /** Samples appended so far, gap padding included. */
    var head = 0L
        private set

    // Chunk s covers [ends[s - 1], ends[s]) and was handed over at times[s].
    private val ends = LongArray(STAMPS)
    private val times = LongArray(STAMPS)
    private var stamps = 0L
    private var lastNs = 0L

    fun append(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        nowNs: Long,
    ) {
        val n = length / 2
        if (n == 0) return
        if (stamps > 0) {
            val elapsed = (nowNs - lastNs) * sampleRate / NANOS
            val missing = elapsed - n
            if (missing > gapMs.toLong() * sampleRate / 1000) {
                val pad = min(missing, capacity.toLong())
                for (i in 0 until pad) put(0f)
                stamp(nowNs - n * NANOS / sampleRate)
            }
        }
        for (i in 0 until n) {
            val lo = pcm[offset + 2 * i].toInt() and 0xFF
            val hi = pcm[offset + 2 * i + 1].toInt()
            put(((hi shl 8) or lo).toShort() / PCM_SCALE)
        }
        stamp(nowNs)
        lastNs = nowNs
    }

    private fun put(v: Float) {
        samples[(head % capacity).toInt()] = v
        head++
    }

    private fun stamp(ns: Long) {
        val i = (stamps % STAMPS).toInt()
        ends[i] = head
        times[i] = ns
        stamps++
    }

    private fun firstStamp(): Long = max(0L, stamps - STAMPS)

    /** Oldest sample index whose data and hand-over time are both still held. */
    val oldest: Long
        get() {
            val bySamples = max(0L, head - capacity)
            val byStamps = if (stamps > STAMPS) ends[(firstStamp() % STAMPS).toInt()] else 0L
            return max(bySamples, byStamps)
        }

    /** When the chunk holding sample [index] was handed over, or null if it's no longer held. */
    fun timeOf(index: Long): Long? {
        if (index < oldest || index >= head) return null
        var lo = firstStamp()
        var hi = stamps - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[(mid % STAMPS).toInt()] > index) hi = mid else lo = mid + 1
        }
        return times[(lo % STAMPS).toInt()]
    }

    /** First sample handed over at or after [ns] (clamped to what's held). */
    fun indexAtOrAfter(ns: Long): Long {
        var lo = firstStamp()
        var hi = stamps
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[(mid % STAMPS).toInt()] >= ns) hi = mid else lo = mid + 1
        }
        if (lo >= stamps) return head
        val start = if (lo == 0L) 0L else ends[((lo - 1) % STAMPS).toInt()]
        return max(start, oldest)
    }

    fun copy(
        from: Long,
        count: Int,
    ): FloatArray? {
        if (from < oldest || from + count > head) return null
        return FloatArray(count) { samples[((from + it) % capacity).toInt()] }
    }

    private companion object {
        const val STAMPS = 4096
        const val NANOS = 1_000_000_000L
        const val PCM_SCALE = 32768f
    }
}

/**
 * Measures, during a real call, how long the far end's voice takes to come back as echo in the
 * mic audio we send, and how loud it is.
 *
 * Taps both directions where the app hands them on: the far-end audio as it arrives from the
 * adapter ([onDownlink]) and the mic audio as it goes back to the adapter ([onUplink]). Every
 * [PERIOD_MS] it cross-correlates the last [UPLINK_WINDOW_MS] of mic audio against the far-end
 * audio that could have caused it (GCC-PHAT, at 8 kHz) and logs:
 *  - delay: arrival of a far-end sample to sending its echo — the delay the adapter's echo
 *    canceller (EchoLatency / echoDelay) has to cover, minus the USB hops;
 *  - echo: the one-tap gain from far-end audio to mic audio, and ncc (how much of the mic
 *    signal that echo explains);
 *  - the app's own buffering at that moment ([bufferStats]).
 * Only numbers are logged; no audio is kept beyond the few seconds held in memory.
 *
 * A window only matches while the far end talks and the cabin is otherwise quiet; windows with
 * double talk or silence are reported as such and left out of the call summary ([stop]).
 */
internal class CallEchoMonitor(
    val sampleRate: Int,
    private val bufferStats: () -> String,
    private val routing: () -> String,
    private val log: (String) -> Unit,
) {
    enum class Status { WARMUP, FAR_QUIET, NO_MATCH, MATCH }

    data class Measurement(
        val status: Status,
        val delayMs: Double? = null,
        val echoDb: Double? = null,
        val ncc: Double? = null,
        val pnrDb: Double? = null,
        val farDbfs: Double? = null,
        val micDbfs: Double? = null,
    )

    private val lock = Any()
    private val down = EchoTimeline(sampleRate, HISTORY_S)
    private val up = EchoTimeline(sampleRate, HISTORY_S)
    private val matches = mutableListOf<Measurement>()
    private var windows = 0
    private var lastRunNs = 0L
    private var routingLogged = false
    private val busy = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "EchoMonitor").apply {
                isDaemon = true
                priority = WORKER_PRIORITY
            }
        }

    /** Far-end PCM (mono, [sampleRate]) as it arrives from the adapter. */
    fun onDownlink(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        nowNs: Long = System.nanoTime(),
    ) {
        synchronized(lock) { down.append(pcm, offset, length, nowNs) }
    }

    /** Mic PCM as it's sent to the adapter. Also paces the analysis. */
    @Suppress("SwallowedException") // rejected = the monitor was stopped mid-call; nothing to do
    fun onUplink(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size,
        nowNs: Long = System.nanoTime(),
    ) {
        synchronized(lock) { up.append(pcm, offset, length, nowNs) }
        if (nowNs - lastRunNs < PERIOD_MS * NANOS_PER_MS || stopped.get()) return
        if (!busy.compareAndSet(false, true)) return
        lastRunNs = nowNs
        try {
            worker.execute {
                try {
                    report(measure())
                } catch (e: Exception) {
                    log("[ECHO_MON] analysis failed: ${e.message}")
                } finally {
                    busy.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            busy.set(false)
        }
    }

    /** Stop analysing and log the call's summary. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        worker.shutdownNow()
        val m = synchronized(lock) { matches.toList() }
        if (m.isEmpty()) {
            log("[ECHO_MON] call summary: no echo match in $windows windows (${sampleRate}Hz)")
            return
        }
        val delays = m.mapNotNull { it.delayMs }
        log(
            "[ECHO_MON] call summary: ${m.size}/$windows windows matched, " +
                "delay median=${fmt(EchoDsp.median(delays))}ms min=${fmt(delays.minOrNull())} " +
                "max=${fmt(delays.maxOrNull())}, echo median=${fmt(EchoDsp.median(m.mapNotNull { it.echoDb }), 1)}dB, " +
                "ncc median=${fmt(EchoDsp.median(m.mapNotNull { it.ncc }), 2)}",
        )
    }

    private fun report(m: Measurement) {
        if (m.status == Status.WARMUP) return
        if (!routingLogged) {
            routingLogged = true
            log("[ECHO_MON] routing: ${routing()}")
        }
        val levels = "far=${fmt(m.farDbfs)}dBFS mic=${fmt(m.micDbfs)}dBFS | ${bufferStats()}"
        val line =
            when (m.status) {
                Status.MATCH ->
                    "[ECHO_MON] delay=${fmt(m.delayMs)}ms echo=${fmt(m.echoDb, 1)}dB ncc=${fmt(m.ncc, 2)} " +
                        "pnr=${fmt(m.pnrDb)}dB $levels"
                Status.NO_MATCH ->
                    "[ECHO_MON] no echo match (best delay=${fmt(m.delayMs)}ms pnr=${fmt(m.pnrDb)}dB " +
                        "ncc=${fmt(m.ncc, 2)}) $levels"
                else -> "[ECHO_MON] far end quiet $levels"
            }
        log(line)
    }

    /** Analyse the latest window now (the worker calls this; tests call it directly). */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    internal fun measure(): Measurement {
        val ulN = sampleRate * UPLINK_WINDOW_MS / 1000
        val ulStart: Long
        val ul: FloatArray
        val dlStart: Long
        val dl: FloatArray
        synchronized(lock) {
            if (up.head - up.oldest < ulN) return Measurement(Status.WARMUP)
            ulStart = up.head - ulN
            val tStart = up.timeOf(ulStart) ?: return Measurement(Status.WARMUP)
            dlStart = down.indexAtOrAfter(tStart - (MAX_DELAY_MS + MARGIN_MS) * NANOS_PER_MS)
            val dlCount = (down.head - dlStart).toInt()
            if (dlCount < ulN + sampleRate / 10) return Measurement(Status.FAR_QUIET)
            ul = up.copy(ulStart, ulN) ?: return Measurement(Status.WARMUP)
            dl = down.copy(dlStart, dlCount) ?: return Measurement(Status.WARMUP)
            windows++
        }

        // Speech echo sits below 4 kHz; 8 kHz keeps the FFTs small.
        val factor = if (sampleRate > ANALYSIS_RATE) sampleRate / ANALYSIS_RATE else 1
        val ulA = if (factor == 2) EchoDsp.decimate2(ul) else ul
        val dlA = if (factor == 2) EchoDsp.decimate2(dl) else dl
        val maxLag = dlA.size - ulA.size
        val micDbfs = EchoDsp.rmsDbfs(ulA)

        val corr = EchoDsp.crossCorrelate(dlA, ulA, maxLag, phat = true)
        val peak = EchoDsp.peakAbs(corr)
        val k = peak.index
        val farDbfs = EchoDsp.rmsDbfs(dlA, k, ulA.size)
        if (farDbfs < FAR_MIN_DBFS) return Measurement(Status.FAR_QUIET, farDbfs = farDbfs, micDbfs = micDbfs)

        val pnr = EchoDsp.peakToNoiseDb(corr, k, ANALYSIS_RATE * PEAK_EXCLUDE_MS / 1000)
        val dot = EchoDsp.dotAt(dlA, ulA, k)
        val eDl = EchoDsp.energy(dlA, k, ulA.size)
        val eUl = EchoDsp.energy(ulA)
        val ncc = if (eDl > 0 && eUl > 0) abs(dot) / sqrt(eDl * eUl) else 0.0
        val echoDb = if (eDl > 0) EchoDsp.db(abs(dot) / eDl) else null

        // Delay = when the mic sample was sent minus when the far-end sample it echoes arrived.
        val delays =
            synchronized(lock) {
                listOf(0.25, 0.5, 0.75).mapNotNull { q ->
                    val off = (ulN * q).toLong()
                    val tUp = up.timeOf(ulStart + off)
                    val tDown = down.timeOf(dlStart + k.toLong() * factor + off)
                    if (tUp != null && tDown != null) (tUp - tDown) / NANOS_PER_MS.toDouble() else null
                }
            }
        val delayMs = EchoDsp.median(delays)
        val status = if (pnr >= PNR_MIN_DB && delayMs != null) Status.MATCH else Status.NO_MATCH
        val m = Measurement(status, delayMs, echoDb, ncc, pnr, farDbfs, micDbfs)
        if (status == Status.MATCH) synchronized(lock) { matches.add(m) }
        return m
    }

    private fun fmt(
        v: Double?,
        decimals: Int = 0,
    ): String = v?.let { "%.${decimals}f".format(it) } ?: "?"

    private companion object {
        const val HISTORY_S = 8
        const val PERIOD_MS = 3_000L
        const val UPLINK_WINDOW_MS = 2_000
        const val MAX_DELAY_MS = 2_000L
        const val MARGIN_MS = 300L
        const val ANALYSIS_RATE = 8_000
        const val PEAK_EXCLUDE_MS = 3
        const val PNR_MIN_DB = 12.0
        const val FAR_MIN_DBFS = -50.0
        const val NANOS_PER_MS = 1_000_000L

        // java priority 4 maps to Android nice 10 (THREAD_PRIORITY_BACKGROUND).
        const val WORKER_PRIORITY = 4
    }
}
