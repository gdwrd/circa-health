package com.nsheremet.circa

import com.nsheremet.circa.ColmiProtocol.ActivitySample
import com.nsheremet.circa.ColmiProtocol.BatteryStatus
import com.nsheremet.circa.ColmiProtocol.HrSample
import com.nsheremet.circa.ColmiProtocol.Spo2Sample
import com.nsheremet.circa.KeepfitProtocol.Minute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Frames named "real" are copied from emu/testdata/sr101/capture1.txt … capture3.txt; "synthetic" ones were never seen on the ring. */
class KeepfitProtocolTest {
    private val K = KeepfitProtocol
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin = ZoneId.of("Europe/Berlin")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun hex(s: String): ByteArray = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()
    /** Ring seconds for a wall-clock time on 2026-11-18. */
    private fun sec(h: Int, m: Int): Int = LocalDateTime.of(2026, 11, 18, h, m).toEpochSecond(ZoneOffset.UTC).toInt()
    /** Synthetic history frame: cmd, ts u32 LE, then data bytes. */
    private fun synth(cmd: Int, ts: Int, vararg data: Int): ByteArray =
        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(cmd.toByte()).putInt(ts).put(data.map { it.toByte() }.toByteArray()).array()

    // capture3: the 16 reply and the five empty history replies
    private val hrDay = hex("16 f0 80 ea fc 6a 01 00 00 00 00 00 00 00 00 00 00 00 00 00")
    private val hrBlock = hex("16 aa 01 a8 a3 fd 6a 01 00 00 00 00 00 00 00 00 00 00 00 00")
    private val hrData = hex("16 a0 a8 a3 fd 6a 00 00 47 47 47 47 47 47 47 47 47 43 47 47")
    private val empties = listOf(
        "39 7c 38 fe 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        "40 7c 38 fe 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        "55 7c 38 fe 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        "10 7c 38 fe 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        "25 98 3a fe 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
    ).map(::hex)

    @Test fun buildersAreExactlyTheAllowlist() {
        val built = listOf(
            K.setTimeRequest(), K.activityRequest(), K.batteryRequest(), K.infoRequest(), K.capabilitiesRequest(),
            K.stepsSleepRequest(0), K.hrRequest(0), K.temperatureHistoryRequest(0), K.spo2Request(0), K.stressRequest(0),
            K.hrLogRequest(), K.temperatureModeRequest(true), K.backgroundSpo2Request(true),
        )
        assertEquals(listOf(0x01, 0x03, 0x0B, 0x0C, 0x20, 0x10, 0x16, 0x39, 0x40, 0x55, 0x19, 0x37, 0x3E), built.map { it[0].toInt() })
        assertTrue(built.all { it.size == 20 })
        // nothing else in the object can make a frame
        val makers = K::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.returnType == ByteArray::class.java && '$' !in it.name }.map { it.name }.toSet()
        assertEquals(setOf("setTimeRequest", "activityRequest", "batteryRequest", "infoRequest", "capabilitiesRequest",
            "stepsSleepRequest", "hrRequest", "temperatureHistoryRequest", "spo2Request", "stressRequest",
            "hrLogRequest", "temperatureModeRequest", "backgroundSpo2Request"), makers)
    }

    @Test fun requestsMatchTheFramesSent() {   // real: the frames the PC sent in capture1 … capture3
        assertArrayEquals(hex("0b 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.batteryRequest())
        assertArrayEquals(hex("01 b6 a3 fd 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.setTimeRequest(ms("2026-11-18T13:10:14Z"), utc))
        assertArrayEquals(hex("37 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.temperatureModeRequest(true))
        assertArrayEquals(hex("37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.temperatureModeRequest(false))
        assertArrayEquals(hex("16 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.hrRequest(0))
        assertArrayEquals(hex("10 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), K.stepsSleepRequest(1))
        // capture3 sent 17 38 (23:56), a typing slip; the builder ends at 23:59
        assertArrayEquals(hex("19 00 00 17 3b 01 05 01 00 00 00 00 00 00 00 00 00 00 00 00"), K.hrLogRequest())
    }

    @Test(expected = IllegalArgumentException::class) fun dayOutOfRange() { K.hrRequest(7) }

    @Test fun setTimeInANonUtcZone() {
        // summer in Berlin: wall clock is UTC+2, the zone byte is the offset without DST
        val f = K.setTimeRequest(ms("2026-07-01T10:00:00Z"), berlin)
        val wall = LocalDateTime.of(2026, 7, 1, 12, 0).toEpochSecond(ZoneOffset.UTC).toInt()
        assertEquals(wall, ByteBuffer.wrap(f, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, f[5].toInt())
        assertEquals(ms("2026-07-01T10:00:00Z"), K.ringMs(wall.toLong(), berlin))
    }

    @Test fun battery() {   // real: capture1 on the charger, capture2 worn
        assertEquals(BatteryStatus(99, true), K.parseBattery(hex("0b 63 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
        assertEquals(BatteryStatus(99, false), K.parseBattery(hex("0b 63 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")))
        assertNull(K.parseBattery(hrData))
        assertNull(K.parseBattery(byteArrayOf(0x0B, 99, 1)))   // not a 20-byte frame
    }

    @Test fun clock() {   // real: capture2 before and after the clock was set
        val unset = K.parseActivity(hex("03 25 fd fb 66 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), utc)!!
        assertEquals(ms("2024-10-01T13:46:13Z"), unset.clockMs)
        val set = K.parseActivity(hex("03 b8 a3 fd 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), utc)!!
        assertEquals(KeepfitProtocol.DayTotals(ms("2026-11-18T13:10:16Z"), 0, 0, 0), set)
        assertNull(K.parseActivity(hex("13 b8 a3 fd 6a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), utc))
    }

    @Test fun dayTotalsFromTheVendorReplayFrame() {   // not our ring: the replay vector in JRing (docs/SR101.md section 5)
        val t = K.parseActivity(hex("03 2D 58 A4 68 92 05 00 00 51 04 00 00 2E 00 00 00 FC 6C 00"), utc)!!
        assertEquals(listOf(1426, 1105, 46), listOf(t.steps, t.distanceMeters, t.kcal))
    }

    @Test fun info() {   // real: capture1
        assertEquals(KeepfitProtocol.Info(140, "41:42:9F:FF:93:A5", 0x3A, 0x61),
            K.parseInfo(hex("0c 8c 00 41 42 9f ff 93 a5 3a 00 61 00 0d 22 13 5d 55 bc a8")))
    }

    @Test fun capabilities() {   // real: capture1
        assertEquals(KeepfitProtocol.Capabilities(temperature = true, spo2History = true, stressHistory = true, hrv = false),
            K.parseCapabilities(hex("20 62 ec 07 38 09 0a be bb 02 00 1b 00 00 00 00 00 00 00 00")))
    }

    @Test fun liveTemperature() {   // real: capture2
        val t = K.parseTemperature(hex("38 1c 0c 20 0e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), 1234L)!!
        assertEquals(1234L, t.timestampMs)
        assertEquals(31.00f, t.celsius, 0.001f)   // body 36.16 (20 0e) is not kept
        assertNull(K.parseTemperature(hex("38 00 00 20 0e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), 1234L))
    }

    @Test fun heartRateFrame() {   // real: capture3
        // 71 × 6, then 71 71 71 67 71 71 = 70.3
        assertEquals(listOf(HrSample(ms("2026-11-18T13:10:00Z"), 71), HrSample(ms("2026-11-18T13:11:00Z"), 70)), K.parseHr(hrData, utc))
        assertNull(K.parseHr(hrDay, utc))
        assertNull(K.parseHr(hrBlock, utc))
        assertEquals(KeepfitProtocol.HrHead(true, ms("2026-11-18T00:00:00Z"), 1), K.parseHrHead(hrDay, utc))
        assertEquals(KeepfitProtocol.HrHead(false, ms("2026-11-18T13:10:00Z"), 1), K.parseHrHead(hrBlock, utc))
        assertNull(K.parseHrHead(hrData, utc))
    }

    @Test fun heartRateInANonUtcZone() {   // real frame: 13:10 on the ring is 12:10 UTC in Berlin's winter
        assertEquals(ms("2026-11-18T12:10:00Z"), K.parseHr(hrData, berlin)!![0].timestampMs)
        assertEquals(ms("2026-11-17T23:00:00Z"), K.parseHrHead(hrDay, berlin)!!.timestampMs)
    }

    @Test fun heartRateNoneAndZeros() {   // synthetic: 16 FF is in the vendor app only; zeros in a data frame never seen
        assertEquals(emptyList<HrSample>(), K.parseHr(hex("16 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), utc))
        val half = hex("16 a0 a8 a3 fd 6a 00 00 00 00 00 00 00 00 3c 00 40 00 00 00")
        assertEquals(listOf(HrSample(ms("2026-11-18T13:11:00Z"), 62)), K.parseHr(half, utc))
    }

    @Test fun heartRatePer5Min() {   // real frame, then synthetic samples
        val minutes = K.parseHr(hrData, utc)!!
        assertEquals(listOf(HrSample(ms("2026-11-18T13:10:00Z"), 71)), K.hrPer5Min(minutes, ms("2026-11-18T13:15:00Z")))   // 70.5 rounds up
        assertEquals(emptyList<HrSample>(), K.hrPer5Min(minutes, ms("2026-11-18T13:14:59Z")))   // slot not finished
        val t = ms("2026-11-18T13:00:00Z")
        val s = listOf(HrSample(t + 360_000, 80), HrSample(t, 60), HrSample(t + 240_000, 64), HrSample(t + 60_000, 0))
        assertEquals(listOf(HrSample(t, 62), HrSample(t + 300_000, 80)), K.hrPer5Min(s, t + 600_000))
    }

    @Test fun emptyRepliesGiveNoSamples() {   // real: capture3, each stamped 23:45 or 23:54 with zeros
        for (f in empties) {
            assertEquals(emptyList<Minute>(), K.parseSteps(f, utc) + K.parseSpo2(f, utc) + K.parseSleep(f, utc))
            assertEquals(emptyList<KeepfitProtocol.TempSample>(), K.parseTemperatureHistory(f, utc))
            assertNull(K.parseHr(f, utc))
        }
    }

    @Test fun syntheticStepsPerLocalHour() {   // layout from the vendor app; Kolkata is UTC+5:30, so a local hour is not a UTC hour
        val a = K.parseSteps(synth(0x10, sec(12, 45), 10, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5), kolkata)
        val b = K.parseSteps(synth(0x10, sec(13, 0), 7, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255), kolkata)
        assertEquals(listOf(Minute(ms("2026-11-18T07:15:00Z"), 10), Minute(ms("2026-11-18T07:17:00Z"), 20), Minute(ms("2026-11-18T07:29:00Z"), 5)), a)
        assertEquals(listOf(ActivitySample(ms("2026-11-18T06:30:00Z"), 35, 0, 0), ActivitySample(ms("2026-11-18T07:30:00Z"), 270, 0, 0)),
            K.stepsPerHour(b + a, kolkata))
    }

    @Test fun syntheticSleepMinutes() {   // layout from the vendor app; no night recorded yet
        val f = synth(0x11, sec(2, 0), 0, 40, 80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 200)
        val m = K.parseSleep(f, utc)
        assertEquals(15, m.size)   // 0 = awake is kept
        assertEquals(listOf(Minute(ms("2026-11-18T02:00:00Z"), 0), Minute(ms("2026-11-18T02:01:00Z"), 40), Minute(ms("2026-11-18T02:02:00Z"), 80)), m.take(3))
        assertEquals(Minute(ms("2026-11-18T02:14:00Z"), 200), m.last())
        assertEquals(emptyList<Minute>(), K.parseSteps(f, utc))
    }

    @Test fun syntheticSpo2PerHour() {   // layout from the vendor app
        val a = K.parseSpo2(synth(0x40, sec(12, 0), 97, 95, 0, 96), utc)
        val b = K.parseSpo2(synth(0x40, sec(13, 0), 99), utc)
        assertEquals(3, a.size)
        assertEquals(listOf(Spo2Sample(ms("2026-11-18T12:00:00Z"), 96, 95)), K.spo2PerHour(a + b, ms("2026-11-18T13:30:00Z"), utc))   // 13:00 not finished
        assertEquals(emptyList<Spo2Sample>(), K.spo2PerHour(a, ms("2026-11-18T12:59:59Z"), utc))
    }

    @Test fun syntheticTemperatureHistory() {   // layout from the vendor app: 3 × (surface, body) per 5 min
        val t = K.parseTemperatureHistory(synth(0x39, sec(12, 0), 0x1c, 0x0c, 0x20, 0x0e, 0, 0, 0, 0, 0x35, 0x0c, 0x2e, 0x0e), berlin)
        assertEquals(listOf(ms("2026-11-18T11:00:00Z"), ms("2026-11-18T11:10:00Z")), t.map { it.timestampMs })
        assertEquals(31.00f, t[0].celsius, 0.001f)
        assertEquals(31.25f, t[1].celsius, 0.001f)
    }
}
