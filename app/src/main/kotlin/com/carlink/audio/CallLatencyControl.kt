package com.carlink.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [226] Keeps the phone-call playback path short. Pure decisions, no audio objects; the playback
 * loop in [DualStreamAudioManager] asks it what to do. One instance per call ([reset] at call end).
 *
 * Why: caller audio arrives at exactly the rate it plays, so any backlog that forms never drains.
 * Before [226] the idle keep-alive wrote 60 ms of silence every 5 ms loop pass during any gap in the
 * caller's audio until the 325 ms AudioTrack buffer was full, and the caller's next words then
 * played ~325 ms late for the rest of the call ([223] logs: trackFull=true, 975 of 986 writes hit
 * a full track). Here:
 *  - [silenceNeeded]: keep-alive silence only tops the track up to [silenceFloorMs], never fills it;
 *  - [workingFrames]: the track plays from a smaller working size than it allocates;
 *  - [onUnderruns]: if the track still runs dry (crackle), both step up, at most [MAX_STEPS] times
 *    (the last step is the full allocation, as before [226]);
 *  - [trimMs]: if caller audio piles up in the ring beyond [capMs] for [holdMs], drop the oldest
 *    20 ms frames while they are quiet (any frame after [forceMs]) until [targetMs] is left.
 */
internal class CallLatencyControl(
    private val floorStartMs: Int = 40,
    private val floorStepMs: Int = 20,
    private val capMs: Int = 60,
    private val targetMs: Int = 20,
    private val holdMs: Long = 1_000,
    private val forceMs: Long = 5_000,
) {
    /** Underrun steps taken this call. */
    var steps = 0
        private set

    private var overSinceMs = 0L
    private var trimming = false

    val silenceFloorMs: Int get() = floorStartMs + steps * floorStepMs

    /** Write [FRAME_MS] of keep-alive silence now? Only while the track holds less than the floor. */
    fun silenceNeeded(queuedMs: Int): Boolean = queuedMs < silenceFloorMs

    /** The call track's working size: [START_MULTIPLIER] minimum buffers, one more per step. */
    fun workingFrames(
        minFrames: Int,
        capacityFrames: Int,
    ): Int = (minFrames * (START_MULTIPLIER + steps)).coerceIn(1, capacityFrames)

    /** The track ran dry: step the floor and working size up. False once at [MAX_STEPS]. */
    fun onUnderruns(): Boolean {
        if (steps >= MAX_STEPS) return false
        steps++
        return true
    }

    /**
     * Milliseconds of the ring's oldest audio to drop on this pass (0 or [FRAME_MS]).
     * [quiet]: whether the oldest [FRAME_MS] in the ring is quiet (between words).
     */
    fun trimMs(
        ringMs: Int,
        quiet: Boolean,
        nowMs: Long,
    ): Int {
        if (!trimming) {
            if (ringMs <= capMs) {
                overSinceMs = 0
                return 0
            }
            if (overSinceMs == 0L) overSinceMs = nowMs
            if (nowMs - overSinceMs < holdMs) return 0
            trimming = true
        }
        if (ringMs <= targetMs) {
            trimming = false
            overSinceMs = 0
            return 0
        }
        return if (quiet || nowMs - overSinceMs >= forceMs) FRAME_MS else 0
    }

    /** New call: back to the small working size and floor. */
    fun reset() {
        steps = 0
        overSinceMs = 0
        trimming = false
    }

    companion object {
        const val FRAME_MS = 20

        /** RMS below this counts as quiet for [trimMs]: pauses between words, comfort noise. */
        const val QUIET_DBFS = -42.0

        /** The call track's working size at call start, in minimum buffers (GM: 2 × 81 ms). */
        const val START_MULTIPLIER = 2

        /** Steps up after underruns; with the GM allocation of 4 minimum buffers the last is all of it. */
        const val MAX_STEPS = 2

        private const val MIN_DB = -120.0
        private const val PCM_SCALE = 32768.0

        /** RMS of [length] bytes of 16-bit little-endian PCM, in dBFS. */
        fun rmsDbfs(
            pcm: ByteArray,
            length: Int,
        ): Double {
            val samples = length / 2
            if (samples <= 0) return MIN_DB
            var sum = 0.0
            for (i in 0 until samples) {
                val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                sum += s.toDouble() * s
            }
            val rms = sqrt(sum / samples) / PCM_SCALE
            return if (rms > 0) (20 * log10(rms)).coerceAtLeast(MIN_DB) else MIN_DB
        }
    }
}
