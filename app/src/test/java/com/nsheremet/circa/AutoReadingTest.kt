package com.nsheremet.circa

import com.nsheremet.circa.ColmiProtocol.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoReadingTest {
    private val now = 10 * 3_600_000L
    private val due = now - 30 * 60_000L
    private val ok = BatteryStatus(80, false)

    private fun skip(on: Boolean = true, last: Long = due, battery: BatteryStatus? = ok, worn: Boolean? = true, live: Boolean = false) =
        autoReadingSkip(on, last, now, battery, worn, live)

    @Test fun runsWhenEveryGatePasses() { assertNull(skip()); assertNull(skip(last = 0L)) }
    @Test fun switchOffIsSilent() = assertEquals("", skip(on = false))
    @Test fun under30MinIsSilent() = assertEquals("", skip(last = due + 1))
    @Test fun liveHrWins() = assertEquals("Measure or Breathe is running", skip(live = true))
    @Test fun noBattery() = assertEquals("no battery reading", skip(battery = null))
    @Test fun charging() = assertEquals("charging", skip(battery = BatteryStatus(90, true)))
    @Test fun lowBattery() { assertEquals("battery 20 %", skip(battery = BatteryStatus(20, false))); assertNull(skip(battery = BatteryStatus(21, false))) }
    @Test fun notWorn() = assertEquals("ring not worn", skip(worn = false))
    @Test fun wearUnknown() = assertEquals(true, skip(worn = null)!!.startsWith("wear state unknown"))
}
