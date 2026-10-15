package com.carlink.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signal math for the echo diagnostics ([EchoTest], [CallEchoMonitor]): FFT cross-correlation,
 * the test sweeps, and level helpers. Pure functions, no Android types, unit-tested.
 */
@Suppress("TooManyFunctions") // a toolbox of small, independent signal helpers
internal object EchoDsp {
    data class Peak(
        val index: Int,
        val value: Double,
    )

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** In-place iterative radix-2 FFT. Size must be a power of two; the inverse is scaled by 1/n. */
    fun fft(
        re: DoubleArray,
        im: DoubleArray,
        inverse: Boolean,
    ) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var cRe = 1.0
                var cIm = 0.0
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val vRe = re[b] * cRe - im[b] * cIm
                    val vIm = re[b] * cIm + im[b] * cRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    val next = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = next
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    /**
     * `r[k] = sum over n of x[n + k] * y[n]`, for k in 0..[maxLag]: where the template [y] sits
     * inside the longer [x]. With [phat] the spectrum is whitened first (GCC-PHAT), which turns
     * speech's broad correlation hump into a sharp peak at the delay; the values are then only
     * good for locating the peak, not for levels.
     */
    fun crossCorrelate(
        x: FloatArray,
        y: FloatArray,
        maxLag: Int,
        phat: Boolean = false,
    ): DoubleArray {
        val n = nextPow2(x.size + y.size)
        val xr = DoubleArray(n)
        val xi = DoubleArray(n)
        val yr = DoubleArray(n)
        val yi = DoubleArray(n)
        for (i in x.indices) xr[i] = x[i].toDouble()
        for (i in y.indices) yr[i] = y[i].toDouble()
        fft(xr, xi, inverse = false)
        fft(yr, yi, inverse = false)
        var maxMag = 0.0
        for (i in 0 until n) {
            // X * conj(Y)
            val re = xr[i] * yr[i] + xi[i] * yi[i]
            val im = xi[i] * yr[i] - xr[i] * yi[i]
            xr[i] = re
            xi[i] = im
            if (phat) maxMag = max(maxMag, sqrt(re * re + im * im))
        }
        if (phat) {
            // Floor keeps empty bins (zero padding, band edges) from being blown up to unit weight.
            val floor = maxMag * PHAT_FLOOR
            for (i in 0 until n) {
                val mag = sqrt(xr[i] * xr[i] + xi[i] * xi[i]) + floor
                xr[i] /= mag
                xi[i] /= mag
            }
        }
        fft(xr, xi, inverse = true)
        return DoubleArray(min(maxLag, x.size - 1) + 1) { xr[it] }
    }

    /** Largest |value| in [from, to), with the range clamped to [r]. */
    fun peakAbs(
        r: DoubleArray,
        from: Int = 0,
        to: Int = r.size,
    ): Peak {
        val lo = from.coerceIn(0, r.size - 1)
        val hi = to.coerceIn(lo + 1, r.size)
        var best = lo
        for (i in lo until hi) {
            if (abs(r[i]) > abs(r[best])) best = i
        }
        return Peak(best, r[best])
    }

    /** How far the peak stands above the rest of [r] (RMS, excluding +/-[exclude] around it), in dB. */
    fun peakToNoiseDb(
        r: DoubleArray,
        peak: Int,
        exclude: Int,
    ): Double {
        var sum = 0.0
        var count = 0
        for (i in r.indices) {
            if (abs(i - peak) <= exclude) continue
            sum += r[i] * r[i]
            count++
        }
        if (count == 0 || sum == 0.0) return MAX_DB
        return db(abs(r[peak]) / sqrt(sum / count))
    }

    fun dotAt(
        x: FloatArray,
        y: FloatArray,
        lag: Int,
    ): Double {
        var s = 0.0
        val n = min(y.size, x.size - lag)
        for (i in 0 until n) s += x[lag + i].toDouble() * y[i]
        return s
    }

    fun energy(
        x: FloatArray,
        from: Int = 0,
        count: Int = x.size - from,
    ): Double {
        var s = 0.0
        for (i in from until min(from + count, x.size)) s += x[i].toDouble() * x[i]
        return s
    }

    /** RMS level of a stretch of [-1, 1) samples, in dBFS. */
    fun rmsDbfs(
        x: FloatArray,
        from: Int = 0,
        count: Int = x.size - from,
    ): Double {
        val n = min(count, x.size - from)
        if (n <= 0) return MIN_DB
        return db(sqrt(energy(x, from, n) / n))
    }

    /** 20*log10, floored at [MIN_DB]. */
    fun db(ratio: Double): Double = if (ratio <= 0.0) MIN_DB else max(MIN_DB, 20.0 * log10(ratio))

    /** Halve the rate (16 kHz to 8 kHz): [1/4, 1/2, 1/4] low-pass, then every second sample. */
    fun decimate2(x: FloatArray): FloatArray =
        FloatArray(x.size / 2) { i ->
            val c = 2 * i
            val prev = if (c > 0) x[c - 1] else x[c]
            val next = if (c + 1 < x.size) x[c + 1] else x[c]
            0.25f * prev + 0.5f * x[c] + 0.25f * next
        }

    /**
     * Exponential sine sweep from [f1] to [f2] Hz (swap them for a down-sweep) with raised-cosine
     * fades. An up-sweep and a down-sweep barely correlate with each other, so one can train an
     * echo canceller while the other measures it.
     */
    @Suppress("LongParameterList")
    fun sweep(
        sampleRate: Int,
        seconds: Double,
        f1: Double,
        f2: Double,
        amplitude: Float,
        fadeMs: Int = 20,
    ): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val k = ln(f2 / f1)
        val fade = sampleRate * fadeMs / 1000
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * f1 * seconds / k * (exp(t / seconds * k) - 1)
            val edge = min(i, n - 1 - i)
            val gain = if (edge < fade) 0.5 * (1 - cos(PI * edge / fade)) else 1.0
            (amplitude * gain * sin(phase)).toFloat()
        }
    }

    /** Little-endian 16-bit PCM bytes to floats in [-1, 1). */
    fun pcm16ToFloat(
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ): FloatArray =
        FloatArray(length / 2) { i ->
            val lo = pcm[offset + 2 * i].toInt() and 0xFF
            val hi = pcm[offset + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / PCM_SCALE
        }

    fun shortsToFloat(
        s: ShortArray,
        count: Int = s.size,
    ): FloatArray = FloatArray(count) { s[it] / PCM_SCALE }

    fun floatToShorts(x: FloatArray): ShortArray = ShortArray(x.size) { (x[it] * PCM_MAX).toInt().coerceIn(-PCM_SCALE.toInt(), PCM_MAX.toInt()).toShort() }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    const val MIN_DB = -120.0
    private const val MAX_DB = 120.0
    private const val PHAT_FLOOR = 1e-4
    private const val PCM_SCALE = 32768f
    private const val PCM_MAX = 32767f
}
