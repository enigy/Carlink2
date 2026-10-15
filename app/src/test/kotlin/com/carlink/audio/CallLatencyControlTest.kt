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
    fun backlogIsTrimmedInQuietFramesAfterTheHoldDownToTheTarget() {
        val c = CallLatencyControl()

        assertEquals(0, c.trimMs(ringMs = 50, quiet = true, nowMs = 1_000)) // under the cap
        assertEquals(0, c.trimMs(ringMs = 100, quiet = true, nowMs = 1_000)) // over: hold starts
        assertEquals(0, c.trimMs(ringMs = 100, quiet = true, nowMs = 1_900)) // still holding
        assertEquals(0, c.trimMs(ringMs = 100, quiet = false, nowMs = 2_000)) // trimming, but speech
        assertEquals(20, c.trimMs(ringMs = 100, quiet = true, nowMs = 2_010))
        assertEquals(20, c.trimMs(ringMs = 40, quiet = true, nowMs = 2_020)) // below cap, above target
        assertEquals(0, c.trimMs(ringMs = 20, quiet = true, nowMs = 2_030)) // target reached
        assertEquals(0, c.trimMs(ringMs = 40, quiet = true, nowMs = 2_040)) // under the cap again
    }

    @Test
    fun aBacklogThatNeverGoesQuietIsTrimmedAfterTheForceTime() {
        val c = CallLatencyControl()

        c.trimMs(ringMs = 100, quiet = false, nowMs = 0L + 1) // hold starts at 1
        assertEquals(0, c.trimMs(ringMs = 100, quiet = false, nowMs = 4_000))
        assertEquals(20, c.trimMs(ringMs = 100, quiet = false, nowMs = 5_001))
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
