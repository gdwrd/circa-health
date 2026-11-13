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
        val old = ColmiProtocol.parseMotionPacket(hex("a1 05 0c 40 1f 00 00 00 00 40 1e 80 07 c0 01 b7"))!!
        val samples = listOf(ColmiProtocol.MotionSample(1000, 0, 0), ColmiProtocol.MotionSample(968, 240, 56))
        assertEquals(ColmiProtocol.MotionPacket(0, samples), old) // circa7 and older: 0 = not numbered
        assertEquals(-1000, ColmiProtocol.parseMotionPacket(hex("a1 05 06 c0 e0 00 00 00 00 00 00 00 00 00 00 00"))!!.samples.single().x)
        assertNull(ColmiProtocol.parseMotionPacket(hex("a1 03 0a c0 1d 40 01 40 00 00 00 00 00 00 00 0c"))) // stock 1-a-second packet
        // circa8: the same samples numbered 0x3FFC2 (bits 0-5 = 02 in x, 6-11 = 3F in y, 12-17 = 3F in z)
        assertEquals(ColmiProtocol.MotionPacket(0x3FFC2, samples), ColmiProtocol.parseMotionPacket(hex("a1 05 0c 42 1f 3f 00 3f 00 40 1e 80 07 c0 01 37")))
    }

    @Test fun lostSamplesFromTheSeqGap() {
        fun packet(seq: Int, n: Int) = ColmiProtocol.MotionPacket(seq, List(n) { ColmiProtocol.MotionSample(0, 0, 0) })
        assertEquals(0, lostSamples(0, packet(7, 2)))            // first packet of a recording
        assertEquals(0, lostSamples(7, packet(9, 2)))
        assertEquals(0, lostSamples(9, packet(10, 1)))
        assertEquals(4, lostSamples(10, packet(16, 2)))          // two 2-sample packets missing
        assertEquals(0, lostSamples(ColmiProtocol.SEQ_MAX, packet(2, 2)))   // wrap skips 0
        assertEquals(3, lostSamples(ColmiProtocol.SEQ_MAX - 1, packet(4, 2)))
        assertEquals(0, lostSamples(0, packet(0, 2)))            // not numbered
    }

    @Test fun motionOnlyRequests() {
        assertArrayEquals(hex("a1 07 00 00 00 00 00 00 00 00 00 00 00 00 00 a8"), ColmiProtocol.motionOnlyStartRequest())
        assertArrayEquals(hex("a1 08 00 00 00 00 00 00 00 00 00 00 00 00 00 a9"), ColmiProtocol.motionOnlyStopRequest())
    }

    @Test fun tapPacketFromPatchedFirmware() {
        assertEquals(2, ColmiProtocol.parseTapPacket(hex("a1 09 02 00 00 00 00 00 00 00 00 00 00 00 00 ac"))) // captured from the emulated patched firmware
        assertNull(ColmiProtocol.parseTapPacket(hex("a1 05 06 c0 e0 00 00 00 00 00 00 00 00 00 00 00")))
        assertNull(ColmiProtocol.parseTapPacket(hex("a1 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 a0")))
    }

    @Test fun lightPacketFromPatchedFirmware() {
        // captured from the emulated patched firmware (circa9): four samples, 3 bytes little-endian each
        assertEquals(listOf(0x0306C9, 0x03065B, 0x030601, 0x0305BB), ColmiProtocol.parseLightPacket(hex("a1 0c 0c c9 06 03 5b 06 03 01 06 03 bb 05 03 bc")))
        assertEquals(listOf(0x030572), ColmiProtocol.parseLightPacket(hex("a1 0c 03 72 05 03 00 00 00 00 00 00 00 00 00 2a")))
        assertNull(ColmiProtocol.parseLightPacket(hex("a1 05 06 c0 e0 00 00 00 00 00 00 00 00 00 00 00")))
        assertArrayEquals(hex("a1 0a 00 00 00 00 00 00 00 00 00 00 00 00 00 ab"), ColmiProtocol.lightOnRequest())
        assertArrayEquals(hex("a1 0b 00 00 00 00 00 00 00 00 00 00 00 00 00 ac"), ColmiProtocol.lightOffRequest())
        assertEquals("circa-light-20261113-0905.csv", lightFile(java.io.File("nowhere"), java.time.LocalDateTime.of(2026, 11, 13, 9, 5)).name)
    }

    @Test fun wearPacketFromPatchedFirmware() {
        // captured from the emulated patched firmware (circaA), clock set to 2025-01-20 06:00
        fun at(h: Int, m: Int) = java.time.LocalDateTime.of(2025, 1, 20, h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(ColmiProtocol.WearState(null, null), ColmiProtocol.parseWearPacket(hex("a1 0f 00 00 00 00 00 00 00 00 00 00 00 00 00 b0")))
        assertEquals(ColmiProtocol.WearState(true, at(6, 5)), ColmiProtocol.parseWearPacket(hex("a1 0f 01 0c a4 20 2f 00 00 00 00 00 00 00 00 b0")))
        assertEquals(ColmiProtocol.WearState(false, at(6, 10)), ColmiProtocol.parseWearPacket(hex("a1 0f 02 38 a5 20 2f 00 00 00 00 00 00 00 00 de")))
        assertNull(ColmiProtocol.parseWearPacket(hex("a1 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 a0")))   // older firmware
        assertArrayEquals(hex("a1 0f 00 00 00 00 00 00 00 00 00 00 00 00 00 b0"), ColmiProtocol.wearRequest())
    }

    @Test fun motionFileNamesNeverOverwrite() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val t = java.time.LocalDateTime.of(2026, 11, 13, 9, 5)
        val first = motionFile(dir, "walk", t)
        assertEquals("circa-motion-walk-20261113-0905.csv", first.name)
        first.writeText("x")
        assertEquals("circa-motion-walk-20261113-0905-2.csv", motionFile(dir, "walk", t).also { it.writeText("x") }.name)
        assertEquals("circa-motion-walk-20261113-0905-3.csv", motionFile(dir, "walk", t).name)
        assertEquals("circa-motion-run-20261113-0905.csv", motionFile(dir, "run", t).name)
        dir.deleteRecursively()
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
        assertTrue(fw.version, Regex("RY02_3\\.00\\.33_ca\\d{4}").matches(fw.version)) // our build; the part before the last _ must match the ring's
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
