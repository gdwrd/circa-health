package com.nsheremet.circa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Light values as the ring sent them (FW7a recording). */
class LightValueTest {
    @Test fun pulseChannel() {
        assertEquals(1, ColmiProtocol.lightTag(2169000))
        assertEquals(71848, ColmiProtocol.lightReading(2169000))
    }

    @Test fun otherChannel() = assertEquals(0, ColmiProtocol.lightTag(5451))

    @Test fun saturated() {
        assertEquals(1, ColmiProtocol.lightTag(4194266))
        assertTrue(ColmiProtocol.lightReading(4194266) >= 0x1FFF00)
    }
}
