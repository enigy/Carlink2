package com.carlink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EchoDspTest {
    private val sr = 16_000

    private fun noise(
        n: Int,
        amp: Float,
        seed: Int,
    ): FloatArray {
        val r = Random(seed)
        return FloatArray(n) { (r.nextFloat() * 2 - 1) * amp }
    }

    @Test
    fun matchedFilterFindsTheSweepAndItsLevel() {
        val sweep = EchoDsp.sweep(sr, 0.5, 150.0, 7000.0, 0.3f)
        val rec = noise(20_000, 0.01f, 1)
        val lag = 5_000
        for (i in sweep.indices) rec[lag + i] += 0.25f * sweep[i]

        val corr = EchoDsp.crossCorrelate(rec, sweep, rec.size - sweep.size)
        val peak = EchoDsp.peakAbs(corr)

        assertEquals(lag, peak.index)
        assertEquals(0.25, peak.value / EchoDsp.energy(sweep), 0.01)
        assertTrue(EchoDsp.peakToNoiseDb(corr, peak.index, 50) > 30)
    }

    @Test
    fun phatFindsTheDelayOfColouredNoise() {
        // Low-passed noise: a broad autocorrelation, like speech.
        val raw = noise(24_000, 0.3f, 2)
        val far = FloatArray(raw.size) { i -> if (i == 0) raw[0] else 0.7f * raw[i] + 0.3f * raw[i - 1] }
        val delay = 3_210
        val mic = noise(8_000, 0.02f, 3)
        for (i in mic.indices) mic[i] += 0.5f * far[delay + i]

        val corr = EchoDsp.crossCorrelate(far, mic, far.size - mic.size, phat = true)

        assertEquals(delay, EchoDsp.peakAbs(corr).index)
    }

    @Test
    fun upSweepBarelyRegistersOnTheDownSweepFilter() {
        val up = EchoDsp.sweep(sr, 1.0, 150.0, 7000.0, 0.3f)
        val down = EchoDsp.sweep(sr, 1.0, 7000.0, 150.0, 0.3f)
        val rec = FloatArray(40_000)
        for (i in up.indices) rec[4_000 + i] = up[i]

        val matched = EchoDsp.peakAbs(EchoDsp.crossCorrelate(rec, up, rec.size - up.size)).value
        val crossed = EchoDsp.peakAbs(EchoDsp.crossCorrelate(rec, down, rec.size - down.size)).value

        assertTrue(EchoDsp.db(kotlin.math.abs(crossed / matched)) < -20)
    }

    @Test
    fun decimateKeepsALowTone() {
        val tone = FloatArray(1_600) { 0.5f * sin(2 * PI * 300 * it / sr).toFloat() }
        val half = EchoDsp.decimate2(tone)

        assertEquals(800, half.size)
        assertEquals(EchoDsp.rmsDbfs(tone), EchoDsp.rmsDbfs(half, 10, 780), 0.3)
    }
}
