package com.nsheremet.circa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Pulse wave → HR and RMSSD, on real ring recordings (channel-1 rows of recordings/circa-light-*.csv) and a synthetic wave. */
class PulseBeatsTest {
    private fun recording(name: String): PulseHrv? {
        val rows = javaClass.getResource("/pulse-$name.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
            .map { it.split(',').let { (t, v) -> t.toLong() to v.toInt() } }
            .filter { (_, v) -> ColmiProtocol.lightTag(v) == 1 && ColmiProtocol.lightReading(v) < 0x1FFF00 }
        return PulseBeats.hrv(rows.map { ColmiProtocol.lightReading(it.second) }, rows.map { it.first })
    }

    // Expected values: the Python reference of the same algorithm (numpy), and independent checks:
    // spectral-peak HR and sinc-upsampled (x16) max-slope RMSSD.
    @Test fun cleanRecording() {
        val r = recording("20261113-2315")!!
        assertEquals(85.21, r.meanHrBpm, 0.1)
        assertEquals(83.9, r.meanHrBpm, 2.0)   // spectral peak
        assertEquals(23.73, r.rmssdMs, 0.1)
        assertEquals(26.7, r.rmssdMs, 4.0)     // sinc max-slope
        assertEquals(33, r.validIntervals)
        assertTrue(r.validFraction >= 0.8)
    }

    @Test fun cleanStretchThenHoleThenMotion() {
        // a ~30 s gap at 30 s; the stretch after it is motion and is dropped as a whole
        val r = recording("20261115-0038")!!
        assertEquals(80.50, r.meanHrBpm, 0.1)
        assertEquals(80.5, r.meanHrBpm, 2.0)   // spectral peak of the first stretch
        assertEquals(28.85, r.rmssdMs, 0.1)
        assertEquals(30.7, r.rmssdMs, 4.0)     // sinc max-slope
        assertEquals(36, r.validIntervals)
        assertEquals(0.577, r.validFraction, 0.002)
    }

    @Test fun motionRecordingIsRejected() = assertNull(recording("20261113-2240"))

    /** Pulses at known jittered beat times, sampled at 25.1 Hz, inverted like the ring, with drift and noise. */
    private class Synth(jitterMs: Double, respMs: Double, noise: Double, seed: Long) {
        val beatsS = ArrayList<Double>()
        val readings: List<Int>
        val trueRmssd: Double

        init {
            val rnd = Random(seed)
            var t = 1.0
            beatsS += t
            repeat(60) {
                t += (700 + respMs * sin(2 * Math.PI * 0.25 * (t - 1.0)) + rnd.nextGaussian() * jitterMs) / 1000
                beatsS += t
            }
            val rr = beatsS.zipWithNext { a, b -> (b - a) * 1000 }
            trueRmssd = sqrt(rr.zipWithNext { a, b -> (b - a) * (b - a) }.average())
            readings = List(((t + 1.0) * 25.1).toInt()) { i ->
                val ts = i / 25.1
                // 130 ms upstroke, 350 ms decay
                val x = beatsS.sumOf { b -> val tau = ts - b; 1 / (1 + exp(-tau / 0.03)) * exp(-max(tau, 0.0) / 0.35) }
                (60000 - 800 * (x + rnd.nextGaussian() * noise + 0.5 * sin(2 * Math.PI * 0.07 * ts))).toInt()
            }
        }

        // Bluetooth delivers bursts: five samples share one arrival time
        fun arrivals(): List<Long> = List(readings.size) { (it / 5) * 199L }
    }

    private fun synthError(jitterMs: Double, respMs: Double, seed: Long): Pair<Double, Double> {
        val s = Synth(jitterMs, respMs, 0.03, seed)
        val r = PulseBeats.hrv(s.readings, s.arrivals())!!
        val trueHr = 60_000.0 / (s.beatsS.zipWithNext { a, b -> (b - a) * 1000 }.average())
        assertEquals(trueHr, r.meanHrBpm, 0.5)
        println("synthetic jitter $jitterMs resp $respMs: true RMSSD %.2f computed %.2f error %+.2f ms".format(s.trueRmssd, r.rmssdMs, r.rmssdMs - s.trueRmssd))
        return s.trueRmssd to r.rmssdMs
    }

    @Test fun syntheticModerateVariability() = synthError(10.0, 25.0, 1).let { (t, c) -> assertEquals(t, c, 3.0) }

    @Test fun syntheticHighVariability() = synthError(25.0, 45.0, 2).let { (t, c) -> assertEquals(t, c, 3.0) }

    // Timing noise adds a floor: at ~5 ms true RMSSD the result reads ~4 ms high (Python, 20 seeds: +3.9 ± 1.0).
    @Test fun syntheticLowVariability() = synthError(3.0, 5.0, 3).let { (t, c) -> assertTrue(c - t > -2.0 && c - t < 7.0) }

    @Test fun noIntervalAcrossAHole() {
        val s = Synth(10.0, 25.0, 0.03, 4)
        // drop 3 samples (120 ms) mid-diastole: an interval formed across the gap would still look plausible
        val cut = ((s.beatsS[30] + 0.35) * 25.1).toInt()
        val readings = s.readings.filterIndexed { i, _ -> i !in cut until cut + 3 }
        val batchy = s.arrivals().filterIndexed { i, _ -> i !in cut until cut + 3 }
        val withHole = batchy.mapIndexed { i, t -> if (i >= cut) t + 1200 else t }
        val split = PulseBeats.hrv(readings, withHole)!!
        val unseen = PulseBeats.hrv(readings, batchy)!!
        // the same samples without the arrival gap form a plausible-looking interval across it (measured: 28.1 vs 24.4 ms)
        assertTrue(split.validIntervals < unseen.validIntervals)
        assertTrue(abs(unseen.rmssdMs - s.trueRmssd) > abs(split.rmssdMs - s.trueRmssd) + 2)
        assertEquals(s.trueRmssd, split.rmssdMs, 3.0)
    }

    @Test fun shortReadingIsRejected() {
        val s = Synth(10.0, 25.0, 0.03, 5)
        val n = (15 * 25.1).toInt()   // 15 s of clean beats is below the 20 s minimum
        assertNull(PulseBeats.hrv(s.readings.take(n), s.arrivals().take(n)))
    }
}
