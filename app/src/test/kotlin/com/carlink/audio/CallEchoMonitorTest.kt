package com.carlink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

class CallEchoMonitorTest {
    private val sr = 16_000
    private val chunk = 320 // 20 ms
    private val ms = 1_000_000L

    private fun pcm(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** Far end: [seconds] of noise; mic: the far end, 0.3x, [delayChunks] later, plus cabin noise. */
    private fun feed(
        monitor: CallEchoMonitor,
        seconds: Int,
        delayChunks: Int,
        farAmp: Float,
    ) {
        val r = Random(7)
        val total = seconds * sr
        val far = FloatArray(total) { (r.nextFloat() * 2 - 1) * farAmp }
        val delay = delayChunks * chunk
        val mic = FloatArray(total) { i -> (if (i >= delay) 0.3f * far[i - delay] else 0f) + (r.nextFloat() * 2 - 1) * 0.005f }
        for (c in 0 until total / chunk) {
            val at = (c + 1) * 20 * ms
            monitor.onDownlink(pcm(far.copyOfRange(c * chunk, (c + 1) * chunk)), 0, chunk * 2, at)
            monitor.onUplink(pcm(mic.copyOfRange(c * chunk, (c + 1) * chunk)), 0, chunk * 2, at + 2 * ms)
        }
    }

    private fun monitor() = CallEchoMonitor(sr, { "" }, { "" }, {}).also { it.stop() }

    @Test
    fun measuresTheEchoDelayAndLevel() {
        val m = monitor()
        feed(m, seconds = 6, delayChunks = 20, farAmp = 0.2f) // 400 ms

        val result = m.measure()

        assertEquals(CallEchoMonitor.Status.MATCH, result.status)
        assertNotNull(result.delayMs)
        assertEquals(402.0, result.delayMs!!, 25.0) // +2 ms: the mic chunk leaves after the far-end packet
        assertEquals(EchoDsp.db(0.3), result.echoDb!!, 1.5)
    }

    @Test
    fun quietFarEndIsNotAMatch() {
        val m = monitor()
        feed(m, seconds = 6, delayChunks = 20, farAmp = 0.0005f)

        assertEquals(CallEchoMonitor.Status.FAR_QUIET, m.measure().status)
    }

    @Test
    fun adapterWindowMatchesTheFirmwareTailAndPreDelay() {
        assertEquals(0..150, CallEchoMonitor.adapterWindow(150)) // short: plain tail
        assertEquals(0..200, CallEchoMonitor.adapterWindow(200))
        assertEquals(120..320, CallEchoMonitor.adapterWindow(320)) // firmware default
        assertEquals(540..740, CallEchoMonitor.adapterWindow(740))
    }

    @Test
    fun timelinePadsGapsAndKeepsTheirTime() {
        val t = EchoTimeline(sr, 4)
        val block = ByteArray(chunk * 2)
        t.append(block, 0, block.size, 20 * ms)
        t.append(block, 0, block.size, 520 * ms) // 480 ms of silence suppression

        assertEquals(8_320L, t.head) // 320 + 7680 padding + 320
        assertEquals(20 * ms, t.timeOf(0))
        assertEquals(500 * ms, t.timeOf(5_000))
        assertEquals(520 * ms, t.timeOf(8_319))
        assertEquals(320L, t.indexAtOrAfter(21 * ms))
    }
}
