package com.nsheremet.circa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Motion log packets as emu/test_ringemu.py's test_motion_log_* see them (FW8c, ca0008). */
class MotionLogTest {
    private fun pkt(vararg parts: Any): ByteArray {
        val b = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        for (p in parts) when (p) { is Byte -> b.put(p); is Int -> b.putInt(p); else -> error(p) }
        return ColmiProtocol.buildPacket(b.array())
    }
    private val a1: Byte = 0xA1.toByte()
    private val six = 790_668_000          // 2025-01-20 06:00 on the ring's clock (cmdmap.SET_TIME)

    @Test fun requests() {
        assertArrayEquals(ColmiProtocol.buildPacket(byteArrayOf(a1, 0x0E)), ColmiProtocol.motionInfoRequest())
        val r = ColmiProtocol.motionLogRequest(1000)
        assertEquals(listOf(0xA1, 0x0D, 0xE8, 0x03, 0, 0), r.take(6).map { it.toInt() and 0xff })
        assertEquals((0xA1 + 0x0D + 0xE8 + 0x03) and 0xff, r[15].toInt() and 0xff)
    }

    @Test fun info() {
        assertEquals(ColmiProtocol.MotionInfo(0, 20, 0), ColmiProtocol.parseMotionInfo(pkt(a1, 0x0E.toByte(), 0, 20, 0.toByte())))
        assertEquals(ColmiProtocol.MotionInfo(0, 0, 1), ColmiProtocol.parseMotionInfo(pkt(a1, 0x0E.toByte(), 0, 0, 1.toByte())))
        assertNull(ColmiProtocol.parseMotionInfo(pkt(a1, 0xFF.toByte())))   // older firmware
    }

    @Test fun headWithRecords() {
        val h = pkt(a1, 0x0D.toByte(), 0x80.toByte(), 1000, six, 54.toByte())
        assertEquals(ColmiProtocol.MotionHead(1000, six.toLong(), 54), ColmiProtocol.parseMotionHead(h))
        assertNull(ColmiProtocol.parseMotionRecords(h))
        assertFalse(ColmiProtocol.isMotionBusy(h))
    }

    @Test fun headNothingNewer() {
        val h = pkt(a1, 0x0D.toByte(), 0x80.toByte(), 99999, 0, 0.toByte())
        assertEquals(ColmiProtocol.MotionHead(99999, 0, 0), ColmiProtocol.parseMotionHead(h))
    }

    @Test fun dataTwoRecords() {
        val d = pkt(a1, 0x0D.toByte(), 2.toByte(), 61, 62)
        assertEquals(listOf(61, 62), ColmiProtocol.parseMotionRecords(d))
        assertNull(ColmiProtocol.parseMotionHead(d))
    }

    @Test fun busy() {
        val b = pkt(a1, 0x0D.toByte(), 0xFE.toByte())
        assertTrue(ColmiProtocol.isMotionBusy(b))
        assertNull(ColmiProtocol.parseMotionHead(b))
        assertNull(ColmiProtocol.parseMotionRecords(b))
    }

    @Test fun records() {
        assertEquals(ColmiProtocol.MotionRecord(60, 0, 7, 0, 0), ColmiProtocol.parseMotionRecord(0x0000703C))   // tap-wait minute
        // |a| 125 / 25 in turn on -y: still 0, sampled 60, jerk 49, level 49 (test_motion_log_builds_a_record_every_minute)
        assertEquals(ColmiProtocol.MotionRecord(0, 60, 3, 49 * 16, 49 * 8), ColmiProtocol.parseMotionRecord((60 shl 6) or (3 shl 12) or (49 shl 16) or (49 shl 24)))
        assertEquals(255 * 8, ColmiProtocol.parseMotionRecord(0xFF shl 24).levelMg)   // top bit set: no sign
    }

    @Test fun minute() {
        val want = java.time.LocalDateTime.of(2025, 1, 20, 6, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(want, ColmiProtocol.ringClockMs(six.toLong()))
        assertEquals(want + 60_000, ColmiProtocol.ringClockMs(six + 60L + 17))   // cut to the minute, as A1 0F
    }
}
