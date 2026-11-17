package com.nsheremet.circa

import com.nsheremet.circa.ui.pulseMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ST2: a pulse reading becomes three samples at its end; a failed quality gate sends nothing. */
class PulseMetricsTest {
    @Test fun goodReadingGivesThreeSamplesAtOneTime() {
        val m = pulseMetrics(PulseHrv(meanHrBpm = 62.5, rmssdMs = 41.2, validIntervals = 90, validFraction = 0.74), 1_700_000_000_000)
        assertEquals(listOf("biometric_pulse_rmssd", "biometric_pulse_hr", "biometric_pulse_quality"), m.map { it.name })
        assertEquals(listOf(41.2, 62.5, 0.74), m.map { it.value })
        assertTrue(m.all { it.timestampMs == 1_700_000_000_000 })
    }

    @Test fun failedGateGivesNothing() = assertTrue(pulseMetrics(null, 1_700_000_000_000).isEmpty())
}
