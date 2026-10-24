package com.carlink.media

import android.view.KeyEvent

/**
 * GM's steering-wheel skip buttons as they reach the media session (ported from Carlink3 v31-v33).
 *
 * On the gminfo 3.7 head unit the buttons send KEYCODE_F7 (Next) and KEYCODE_F8 (Back), not
 * MEDIA_NEXT/PREVIOUS. When GM has this app selected as its audio source, GM's own media service
 * turns a press into a skip command (onSkipToNext/Previous, via com.gm.rhmi). Otherwise the press
 * arrives here as the raw key, from "android", and Media3's default handling ignores F7/F8, so the
 * press did nothing. Carlink3 drive log 2026-10-23: five presses, keys 137 and 138; which button is
 * which was confirmed on the truck 2026-10-24.
 */
object SteeringWheelKeys {
    /** True for Next, false for Back, null when [keyCode] is not a steering-wheel skip key. */
    fun direction(keyCode: Int): Boolean? =
        when (keyCode) {
            KeyEvent.KEYCODE_F7 -> true
            KeyEvent.KEYCODE_F8 -> false
            else -> null
        }

    /**
     * The drive-log line for a media button the session received, or null for auto-repeats past the
     * first, so a held key is one "held" line rather than one per repeat. Every press is logged with
     * its sender, so a skip that does nothing can be told apart from one that never arrived.
     */
    fun line(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        from: String,
        outcome: String,
    ): String? {
        if (repeatCount > 1) return null
        val press =
            when {
                action == KeyEvent.ACTION_UP -> "up"
                action != KeyEvent.ACTION_DOWN -> "action $action"
                repeatCount == 0 -> "down"
                else -> "held"
            }
        return "[MEDIA_SESSION] media key $keyCode $press from $from $outcome"
    }
}

/**
 * One skip per press of GM's steering-wheel buttons. A press reaches the app one of two ways: as a
 * skip command from GM's media service when GM has this app selected, or as a raw F7/F8 key when it
 * has not. Should one press ever arrive both ways, the second, within [windowMs] of the first, is
 * dropped. Skips arriving the same way are never dropped, so a quick double press still skips twice.
 * Main thread only, like the session callbacks that feed it.
 */
class SkipGate(
    private val windowMs: Long,
) {
    enum class Path { KEY, COMMAND }

    private var last: Path? = null
    private var lastNext = false
    private var lastAtMs = 0L

    /** True when this skip should reach the phone; [next] is its direction, [nowMs] a monotonic clock. */
    fun allow(
        next: Boolean,
        path: Path,
        nowMs: Long,
    ): Boolean {
        val echo = last != null && path != last && next == lastNext && nowMs - lastAtMs < windowMs
        if (echo) {
            last = null
            return false
        }
        last = path
        lastNext = next
        lastAtMs = nowMs
        return true
    }
}
