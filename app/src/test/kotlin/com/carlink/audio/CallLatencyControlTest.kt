package com.carlink.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLatencyControlTest {
    @Test
    fun silenceOnlyTopsUpToTheFloor() {
        val c = CallLatencyControl()

        assertTrue(c.silenceNeeded(0))
        assertTrue(c.silenceNeeded(39))
        assertFalse(c.silenceNeeded(40))
        assertFalse(c.silenceNeeded(300)) // pre-[226] filled to here
    }

    @Test
    fun underrunsStepTheBufferAndFloorUpToTheAllocation() {
        val c = CallLatencyControl()
        val min = 1_300 // GM: 81 ms at 16 kHz
        val capacity = 4 * min

        assertEquals(2 * min, c.workingFrames(min, capacity))
        assertTrue(c.onUnderruns())
        assertEquals(3 * min, c.workingFrames(min, capacity))
        assertEquals(60, c.silenceFloorMs)
        assertTrue(c.onUnderruns())
        assertEquals(capacity, c.workingFrames(min, capacity))
        assertFalse(c.onUnderruns()) // already at the full allocation
        assertEquals(capacity, c.workingFrames(min, capacity))

        c.reset()
        assertEquals(2 * min, c.workingFrames(min, capacity))
        assertEquals(40, c.silenceFloorMs)
    }

    @Test
    fun theStandingQueueAboveTheFloorIsTrimmedInQuietFrames() {
        val c = CallLatencyControl()

        // v226: 65 ms in the track and 40-90 ms more in the ring all call; it never fell below 105.
        var now = 1_000L
        while (now < 3_000) {
            assertEquals(0, c.trimMs(totalMs = 105 + (now % 50).toInt(), ringMs = 40, quiet = true, nowMs = now))
            now += 10
        }
        // Window closed: 105 - 40 floor = 65 ms never needed → three quiet 20 ms frames.
        assertEquals(0, c.trimMs(totalMs = 110, ringMs = 45, quiet = false, nowMs = 3_000)) // speech: wait
        assertEquals(20, c.trimMs(totalMs = 110, ringMs = 45, quiet = true, nowMs = 3_010))
        assertEquals(20, c.trimMs(totalMs = 90, ringMs = 25, quiet = true, nowMs = 3_020))
        assertEquals(0, c.trimMs(totalMs = 70, ringMs = 5, quiet = true, nowMs = 3_030)) // ring too short
        assertEquals(20, c.trimMs(totalMs = 90, ringMs = 30, quiet = true, nowMs = 3_040))
        assertEquals(0, c.trimMs(totalMs = 70, ringMs = 30, quiet = true, nowMs = 3_050)) // excess used up
    }

    @Test
    fun aQueueThatDipsToTheFloorIsLeftAlone() {
        val c = CallLatencyControl()

        var now = 1_000L
        while (now <= 3_100) {
            val total = if (now % 300 == 0L) 50 else 120 // touches 50: only 10 ms above the floor
            assertEquals(0, c.trimMs(totalMs = total, ringMs = 60, quiet = true, nowMs = now))
            now += 10
        }
    }

    @Test
    fun aStandingQueueThatNeverGoesQuietIsTrimmedAfterTheForceTime() {
        val c = CallLatencyControl()

        var now = 1_000L
        while (now <= 3_000) {
            c.trimMs(totalMs = 200, ringMs = 100, quiet = false, nowMs = now)
            now += 10
        }
        assertEquals(0, c.trimMs(totalMs = 200, ringMs = 100, quiet = false, nowMs = 7_900)) // excess seen at 3000
        assertEquals(20, c.trimMs(totalMs = 200, ringMs = 100, quiet = false, nowMs = 8_000))
    }

    @Test
    fun rmsOfPcm16() {
        val full = ByteArray(640) { if (it % 2 == 0) 0xFF.toByte() else 0x7F } // +32767
        val quiet = ByteArray(640) { if (it % 2 == 0) 10 else 0 } // +10

        assertEquals(0.0, CallLatencyControl.rmsDbfs(full, full.size), 0.01)
        assertEquals(-70.3, CallLatencyControl.rmsDbfs(quiet, quiet.size), 0.1)
        assertTrue(CallLatencyControl.rmsDbfs(quiet, quiet.size) < CallLatencyControl.QUIET_DBFS)
        assertEquals(-120.0, CallLatencyControl.rmsDbfs(ByteArray(640), 640), 0.0)
    }

    @Test
    fun ringBufferPeekLeavesDataAndSkipDropsWholeFrames() {
        val ring = AudioRingBuffer(capacityMs = 10, sampleRate = 16_000, channels = 1) // 320 B
        ring.write(ByteArray(300) { it.toByte() })
        ring.read(ByteArray(250)) // read position near the end, so the next write wraps
        ring.write(ByteArray(100) { (it + 100).toByte() })

        val peeked = ByteArray(80)
        assertEquals(80, ring.peek(peeked))
        assertEquals(150, ring.availableForRead()) // nothing consumed
        assertArrayEquals(ByteArray(50) { (it + 250).toByte() }, peeked.copyOfRange(0, 50))
        assertArrayEquals(ByteArray(30) { (it + 100).toByte() }, peeked.copyOfRange(50, 80)) // across the wrap

        assertEquals(50, ring.skip(51)) // frame-aligned: 2-byte mono frames
        assertEquals(100, ring.availableForRead())
        val next = ByteArray(2)
        ring.read(next)
        assertArrayEquals(byteArrayOf(100, 101), next)
    }
}
