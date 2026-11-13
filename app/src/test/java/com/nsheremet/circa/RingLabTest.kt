package com.nsheremet.circa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingLabTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun crcIsModbus() {
        assertEquals(0x4B37, crc16("123456789".toByteArray()))
        assertEquals(0xFFFF, crc16(ByteArray(0)))
    }

    @Test fun updatePacketsMatchTheFlasher() {
        assertArrayEquals(hex("bc 01 00 00 ff ff"), updatePacket(1))
        assertArrayEquals(hex("bc 03 01 00 bf 40 00"), updatePacket(3, byteArrayOf(0))) // the ring's own "part accepted" answer, seen in the emulator
    }

    @Test fun motionPacketFromPatchedFirmware() {
        // captured from the emulated patched firmware
        val samples = ColmiProtocol.parseMotionPacket(hex("a1 05 0c 40 1f 00 00 00 00 40 1e 80 07 c0 01 b7"))
        assertEquals(listOf(ColmiProtocol.MotionSample(1000, 0, 0), ColmiProtocol.MotionSample(968, 240, 56)), samples)
        assertEquals(-1000, ColmiProtocol.parseMotionPacket(hex("a1 05 06 c0 e0 00 00 00 00 00 00 00 00 00 00 00"))!!.single().x)
        assertNull(ColmiProtocol.parseMotionPacket(hex("a1 03 0a c0 1d 40 01 40 00 00 00 00 00 00 00 0c"))) // stock 1-a-second packet
    }

    /** A minimal well-formed update file: wrapper, Realtek header with [flags], [payload] bytes of code. */
    private fun image(flags: Int, payload: Int = 64): ByteArray {
        val b = ByteArray(0x50 + 0x400 + payload) { (it * 7).toByte() }
        fun le(o: Int, v: Long, n: Int) { for (i in 0 until n) b[o + i] = (v shr (8 * i)).toByte() }
        "RY02_3.00.33_250117".toByteArray().copyInto(b, 0x10); b.fill(0, 0x10 + 19, 0x30)
        "RY02_V3.0".toByteArray().copyInto(b, 0x30); b.fill(0, 0x30 + 9, 0x50)
        le(0x52, flags.toLong(), 2); le(0x54, 0x2793, 2); le(0x58, payload.toLong(), 4)
        le(4, (b.size - 0x50).toLong(), 4); le(8, (b.size - 0x50).toLong(), 4)
        le(0x0C, b.drop(0x50).sumOf { (it.toInt() and 0xff).toLong() }, 4)
        return b
    }

    @Test fun bundledPatchIsInstallable() {
        val fw = FirmwareFile(java.io.File("src/main/assets/RY02_3.00.33_fullaccel.bin").readBytes())
        assertNull(fw.problem)
        assertTrue(fw.modified)
        assertEquals("RY02_3.00.33_250117", fw.version) // must equal what the ring reports, or the updater refuses it
        assertEquals("RY02_V3.0", fw.hardware)
    }

    @Test fun bundledStockIsInstallable() {
        val fw = FirmwareFile(java.io.File("src/main/assets/RY02_3.00.33_250117.bin").readBytes())
        assertNull(fw.problem)
        assertFalse(fw.modified)
        assertEquals("RY02_3.00.33_250117", fw.version)
        assertEquals("RY02_V3.0", fw.hardware)
    }

    @Test fun firmwareFileChecks() {
        val vendor = FirmwareFile(image(0x0981))
        assertNull(vendor.problem)
        assertEquals("RY02_3.00.33_250117", vendor.version)
        assertEquals("RY02_V3.0", vendor.hardware)
        assertFalse(vendor.modified)
        assertTrue(FirmwareFile(image(0x0901)).modified)

        val damaged = image(0x0981).also { it[0x460] = (it[0x460] + 1).toByte() }
        assertNotNull(FirmwareFile(damaged).problem)
        assertNotNull(FirmwareFile(ByteArray(100)).problem)
        assertNotNull(FirmwareFile(image(0x0981).copyOf(0x50 + 0x400 + 32)).problem) // cut short
    }
}
