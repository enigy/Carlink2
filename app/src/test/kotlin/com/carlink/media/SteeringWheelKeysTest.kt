package com.carlink.media

import android.view.KeyEvent
import com.carlink.media.SkipGate.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GM's steering-wheel skip keys, their drive-log line, and one skip per press. */
class SteeringWheelKeysTest {
    @Test
    fun f7IsNextAndF8IsBack() {
        // Confirmed on the truck 2026-10-24 (Carlink3 v32 had them the other way round).
        assertEquals(true, SteeringWheelKeys.direction(KeyEvent.KEYCODE_F7))
        assertEquals(false, SteeringWheelKeys.direction(KeyEvent.KEYCODE_F8))
        assertNull("standard media keys keep Media3's default", SteeringWheelKeys.direction(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun everyPressIsLoggedWithItsSenderButARepeatOnlyOnce() {
        val f7 = KeyEvent.KEYCODE_F7
        assertEquals(
            "[MEDIA_SESSION] media key 137 down from android -> NEXT",
            SteeringWheelKeys.line(f7, KeyEvent.ACTION_DOWN, 0, "android", "-> NEXT"),
        )
        assertEquals(
            "[MEDIA_SESSION] media key 137 up from android (nothing to do)",
            SteeringWheelKeys.line(f7, KeyEvent.ACTION_UP, 0, "android", "(nothing to do)"),
        )
        assertEquals(
            "[MEDIA_SESSION] media key 137 held from android (nothing to do)",
            SteeringWheelKeys.line(f7, KeyEvent.ACTION_DOWN, 1, "android", "(nothing to do)"),
        )
        assertNull(SteeringWheelKeys.line(f7, KeyEvent.ACTION_DOWN, 2, "android", "(nothing to do)"))
    }

    @Test
    fun aPressArrivingBothWaysSkipsOnce() {
        val gate = SkipGate(windowMs = 500)
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_000))
        assertFalse("the raw key for the same press", gate.allow(next = true, Path.KEY, nowMs = 1_120))
        assertTrue("the next press", gate.allow(next = true, Path.KEY, nowMs = 2_000))
    }

    @Test
    fun aQuickDoublePressStillSkipsTwice() {
        val gate = SkipGate(windowMs = 500)
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_000))
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_200))
    }

    @Test
    fun backRightAfterNextIsItsOwnPress() {
        val gate = SkipGate(windowMs = 500)
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_000))
        assertTrue(gate.allow(next = false, Path.KEY, nowMs = 1_100))
    }

    @Test
    fun outsideTheWindowBothCount() {
        val gate = SkipGate(windowMs = 500)
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_000))
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_600))
    }
}
