package com.nsheremet.circa

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Heart rate and RMSSD from one pulse-wave reading; null when the reading is too noisy to trust. */
data class PulseHrv(val meanHrBpm: Double, val rmssdMs: Double, val validIntervals: Int, val validFraction: Double)

/**
 * Beat-to-beat intervals from the ring's raw pulse wave (channel 1, ~25.1 Hz).
 * Fiducial: steepest point of the systolic upstroke, aligned to the stretch's average beat shape.
 */
object PulseBeats {
    private const val FS = 25.1              // samples per second inside a continuous stretch
    private const val HOLE_MS = 1000L        // arrival gap that means samples were lost
    private const val BASE_WIN = 25          // ~1 s centred mean, removed as baseline drift
    private const val POL_WIN = 125          // ~5 s windows that vote on which edge is the upstroke
    private const val REFRACT = 6            // ±240 ms: at most one beat there (caps ~250 bpm)
    private const val AMP_WIN = 50           // ±2 s for the adaptive beat threshold
    private const val AMP_K = 0.35           // an upstroke is at least 35% of the steepest one nearby
    private const val HALF = 5               // ±200 ms of slope compared with the average beat
    private const val SHIFT = 2              // the average beat is searched ±2 samples around each beat
    private const val MIN_CORR = 0.9         // beat shape must match the average beat (0.95 cuts clean beats)
    private const val MIN_RR = 300.0
    private const val MAX_RR = 2000.0
    private const val MED_HALF = 5           // local median over 11 intervals
    private const val MAX_DEV = 0.2          // an interval further than 20% from the local median is an artefact
    private const val MIN_STRETCH_FRACTION = 0.8 // clean recordings score 0.88-0.94, motion 0.63 or less
    private const val MIN_VALID_MS = 20_000.0    // a clean ~30 s stretch yields 23-27 s of valid beats

    /** [readings] are channel-1 values below 0x1FFF00 in arrival order; [arrivalMs] their arrival times. */
    fun hrv(readings: List<Int>, arrivalMs: List<Long>): PulseHrv? {
        require(readings.size == arrivalMs.size)
        var totalMs = 0.0
        val rrs = ArrayList<Double>()
        val diffs = ArrayList<Double>()
        var start = 0
        for (i in 1..readings.size) {
            if (i < readings.size && arrivalMs[i] - arrivalMs[i - 1] < HOLE_MS) continue
            val x = DoubleArray(i - start) { readings[start + it].toDouble() }
            val durMs = x.size / FS * 1000
            totalMs += durMs
            start = i
            val (times, ok) = beats(x) ?: continue
            val rr = DoubleArray(times.size - 1) { times[it + 1] - times[it] }
            val valid = BooleanArray(rr.size) {
                val med = median(rr, max(0, it - MED_HALF), min(rr.size, it + MED_HALF + 1))
                ok[it] && ok[it + 1] && rr[it] in MIN_RR..MAX_RR && abs(rr[it] - med) <= MAX_DEV * med
            }
            val validMs = rr.indices.filter { valid[it] }.sumOf { rr[it] }
            if (validMs / durMs < MIN_STRETCH_FRACTION) continue // too much motion: drop the whole stretch
            for (k in rr.indices) {
                if (valid[k]) rrs += rr[k]
                if (k > 0 && valid[k] && valid[k - 1]) diffs += rr[k] - rr[k - 1]
            }
        }
        val validMs = rrs.sum()
        if (validMs < MIN_VALID_MS || diffs.isEmpty()) return null
        return PulseHrv(
            meanHrBpm = 60_000.0 / rrs.average(),
            rmssdMs = sqrt(diffs.sumOf { it * it } / diffs.size),
            validIntervals = rrs.size,
            validFraction = validMs / totalMs,
        )
    }

    /** Beat times (ms from the stretch start) and whether each beat's shape passed. */
    private fun beats(x: DoubleArray): Pair<DoubleArray, BooleanArray>? {
        val n = x.size
        if (n < 2 * (HALF + SHIFT) + 3) return null
        val base = movingMean(x, BASE_WIN)
        val y = movingMean(DoubleArray(n) { x[it] - base[it] }, 3)
        val d = DoubleArray(n)
        for (i in 1 until n - 1) d[i] = (y[i + 1] - y[i - 1]) / 2
        // raw counts usually fall on the upstroke; the steeper edge of most windows is the upstroke
        var votes = 0
        var s = 0
        do {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in s until min(s + POL_WIN, n)) { hi = max(hi, d[j]); lo = min(lo, d[j]) }
            votes += if (hi > -lo) 1 else -1
            s += POL_WIN / 2
        } while (s < n - POL_WIN + 1)
        if (votes < 0) for (i in d.indices) d[i] = -d[i]

        val idx = ArrayList<Int>()
        for (i in HALF + SHIFT until n - HALF - SHIFT) {
            if (d[i] <= 0) continue
            val lo = max(0, i - REFRACT)
            val hi = min(n, i + REFRACT + 1)
            if ((lo until hi).any { d[it] > d[i] || (it < i && d[it] == d[i]) }) continue
            val ampHi = min(n, i + AMP_WIN + 1)
            if (d[i] < AMP_K * (max(0, i - AMP_WIN) until ampHi).maxOf { d[it] }) continue
            idx += i
        }
        if (idx.size < 3) return null

        // average beat from unit-norm windows so one large artefact cannot dominate it
        val w = 2 * HALF + 1
        val tpl = DoubleArray(w)
        for (i in idx) {
            val win = centred(d, i - HALF, w)
            val norm = sqrt(win.sumOf { it * it }) + 1e-12
            for (k in 0 until w) tpl[k] += win[k] / norm / idx.size
        }
        val tm = tpl.average()
        for (k in 0 until w) tpl[k] -= tm
        val tplNorm = sqrt(tpl.sumOf { it * it })

        val times = DoubleArray(idx.size)
        val ok = BooleanArray(idx.size)
        for ((b, i) in idx.withIndex()) {
            val c = DoubleArray(2 * SHIFT + 1) { k ->
                val win = centred(d, i + k - SHIFT - HALF, w)
                (0 until w).sumOf { win[it] * tpl[it] } / (sqrt(win.sumOf { v -> v * v }) * tplNorm + 1e-12)
            }
            val k = c.indices.maxBy { c[it] }
            // parabola through the correlation peak: one sample is 40 ms, far too coarse for RMSSD
            var off = 0.0
            if (k in 1 until 2 * SHIFT) {
                val den = c[k - 1] - 2 * c[k] + c[k + 1]
                if (den != 0.0) off = 0.5 * (c[k - 1] - c[k + 1]) / den
            }
            times[b] = (i + k - SHIFT + off) / FS * 1000
            ok[b] = c[k] >= MIN_CORR
        }
        return times to ok
    }

    /** Centred mean over [w] samples, shrinking at the edges. */
    private fun movingMean(x: DoubleArray, w: Int): DoubleArray {
        val sum = DoubleArray(x.size + 1)
        for (i in x.indices) sum[i + 1] = sum[i] + x[i]
        val h = w / 2
        return DoubleArray(x.size) {
            val lo = max(0, it - h)
            val hi = min(x.size, it + h + 1)
            (sum[hi] - sum[lo]) / (hi - lo)
        }
    }

    /** [w] samples of [d] from [from], mean removed. */
    private fun centred(d: DoubleArray, from: Int, w: Int): DoubleArray {
        val m = (from until from + w).sumOf { d[it] } / w
        return DoubleArray(w) { d[from + it] - m }
    }

    private fun median(a: DoubleArray, from: Int, to: Int): Double {
        val s = a.copyOfRange(from, to).sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
