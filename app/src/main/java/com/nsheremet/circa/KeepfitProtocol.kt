package com.nsheremet.circa

import com.nsheremet.circa.ColmiProtocol.ActivitySample
import com.nsheremet.circa.ColmiProtocol.BatteryStatus
import com.nsheremet.circa.ColmiProtocol.HrSample
import com.nsheremet.circa.ColmiProtocol.Spo2Sample
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

// The SR101 ring's "keepfit" protocol (docs/SR101.md, docs/PLAN17.md): 20-byte frames, byte 0 the command, no checksum,
// little-endian. The public builders below are the whole set of frames Circa may send to this ring.
object KeepfitProtocol {

    val SERVICE: UUID     = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE: UUID  = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY: UUID = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb")

    // ── Request builders ──────────────────────────────────────────────────

    private fun frame(vararg head: Int): ByteArray = ByteArray(20).also { f -> head.forEachIndexed { i, b -> f[i] = b.toByte() } }

    // day: 0 = today, up to 6 back.
    private fun dayFrame(cmd: Int, day: Int): ByteArray {
        require(day in 0..6) { "day $day" }
        return frame(cmd, day)
    }

    // Clock: local wall-clock seconds as if they were UTC, then the zone's hours without DST (as the vendor app sends).
    fun setTimeRequest(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val now = Instant.ofEpochMilli(nowMs)
        val sec = nowMs / 1000 + zone.rules.getOffset(now).totalSeconds
        return frame(0x01, sec.toInt(), (sec shr 8).toInt(), (sec shr 16).toInt(), (sec shr 24).toInt(),
            zone.rules.getStandardOffset(now).totalSeconds / 3600)
    }

    fun activityRequest(): ByteArray = frame(0x03)
    fun batteryRequest(): ByteArray = frame(0x0B)
    fun infoRequest(): ByteArray = frame(0x0C)
    fun capabilitiesRequest(): ByteArray = frame(0x20)

    fun stepsSleepRequest(day: Int): ByteArray = dayFrame(0x10, day)
    fun hrRequest(day: Int): ByteArray = dayFrame(0x16, day)
    fun temperatureHistoryRequest(day: Int): ByteArray = dayFrame(0x39, day)
    fun spo2Request(day: Int): ByteArray = dayFrame(0x40, day)
    fun stressRequest(day: Int): ByteArray = dayFrame(0x55, day)

    // Heart-rate logging: 00:00 to 23:59, on, every 5 min.
    fun hrLogRequest(): ByteArray = frame(0x19, 0, 0, 23, 59, 1, 5, 1)

    // On gave one `38` reading 2 s later on our ring, not a stream.
    fun temperatureModeRequest(on: Boolean): ByteArray = frame(0x37, if (on) 1 else 0)
    // Background SpO2; read in the vendor app only, never sent to our ring yet.
    fun backgroundSpo2Request(on: Boolean): ByteArray = frame(0x3E, if (on) 1 else 0)

    // ── Data classes ──────────────────────────────────────────────────────

    /** `03`: the ring's clock and the day's totals so far. */
    data class DayTotals(val clockMs: Long, val steps: Int, val distanceMeters: Int, val kcal: Int)

    data class Info(val version: Int, val mac: String, val cid: Int, val did: Int)

    /** What the firmware claims, not proof of a sensor. */
    data class Capabilities(val temperature: Boolean, val spo2History: Boolean, val stressHistory: Boolean, val hrv: Boolean)

    /** Head of a `16` reply. [day] true: `16 F0`, [timestampMs] the day's midnight; false: `16 AA`, the first data frame's time. What [count] counts is unproven (1 seen with one data frame). */
    data class HrHead(val day: Boolean, val timestampMs: Long, val count: Int)

    /** One minute of steps (`10`), sleep (`11`: 0 wake, 1..79 light, 80+ deep) or SpO2 % (`40`). */
    data class Minute(val timestampMs: Long, val value: Int)

    /** Surface (skin) temperature. */
    data class TempSample(val timestampMs: Long, val celsius: Float)

    // ── Parsers ───────────────────────────────────────────────────────────

    /** Ring seconds (local wall-clock counted as if UTC) to epoch ms. */
    fun ringMs(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC).atZone(zone).toInstant().toEpochMilli()

    private fun u8(v: ByteArray, i: Int): Int = v[i].toInt() and 0xff
    private fun u16(v: ByteArray, i: Int): Int = u8(v, i) or (u8(v, i + 1) shl 8)
    private fun u32(v: ByteArray, i: Int): Long = u16(v, i).toLong() or (u16(v, i + 2).toLong() shl 16)
    private fun isFrame(v: ByteArray, cmd: Int): Boolean = v.size >= 20 && u8(v, 0) == cmd

    // State byte: 1 on the charger, 0 off it (two reads of our ring).
    fun parseBattery(value: ByteArray): BatteryStatus? =
        if (isFrame(value, 0x0B)) BatteryStatus(u8(value, 1), u8(value, 2) == 1) else null

    fun parseActivity(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): DayTotals? =
        if (isFrame(value, 0x03)) DayTotals(ringMs(u32(value, 1), zone), u32(value, 5).toInt(), u32(value, 9).toInt(), u32(value, 13).toInt()) else null

    // Bytes 13..19 differ between replies of the same content, so no check is made on them.
    fun parseInfo(value: ByteArray): Info? =
        if (isFrame(value, 0x0C)) Info(u16(value, 1), (3..8).joinToString(":") { "%02X".format(u8(value, it)) }, u16(value, 9), u16(value, 11)) else null

    fun parseCapabilities(value: ByteArray): Capabilities? {
        if (!isFrame(value, 0x20)) return null
        fun bit(k: Int): Boolean = u8(value, 1 + k / 8) and (1 shl (k % 8)) != 0
        return Capabilities(temperature = bit(10), spo2History = bit(81), stressHistory = bit(83), hrv = bit(86))
    }

    fun parseHrHead(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): HrHead? {
        if (!isFrame(value, 0x16)) return null
        return when (u8(value, 1)) {
            0xF0 -> HrHead(true, ringMs(u32(value, 2), zone), u16(value, 6))
            0xAA -> HrHead(false, ringMs(u32(value, 3), zone), u16(value, 7))
            else -> null
        }
    }

    /**
     * `16 A0 ts(4)@2 seq(2)@6 h0..h11@8`: 12 readings over 2 min → two one-minute means of the readings above 0, at ts
     * and ts + 60 (as the vendor app keeps them). Empty for `16 FF` (nothing stored); null for a head or another command.
     */
    fun parseHr(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<HrSample>? {
        if (!isFrame(value, 0x16)) return null
        if (u8(value, 1) == 0xFF) return emptyList()
        if (u8(value, 1) != 0xA0) return null
        val ts = u32(value, 2)
        return (0..1).mapNotNull { m ->
            val h = (0..5).map { u8(value, 8 + 6 * m + it) }.filter { it > 0 }
            if (h.isEmpty()) null else HrSample(ringMs(ts + 60 * m, zone), h.average().roundToInt())
        }
    }

    // `cmd ts(4) v0..v14`: one value a minute from ts.
    private fun minutes(value: ByteArray, cmd: Int, zone: ZoneId, keepZero: Boolean): List<Minute> {
        if (!isFrame(value, cmd)) return emptyList()
        val ts = u32(value, 1)
        return (0..14).map { Minute(ringMs(ts + 60 * it, zone), u8(value, 5 + it)) }.filter { keepZero || it.value > 0 }
    }

    // Layout from the vendor app; our ring has only sent the empty frame so far.
    fun parseSteps(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<Minute> = minutes(value, 0x10, zone, false)
    fun parseSpo2(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<Minute> = minutes(value, 0x40, zone, false)
    // 0 is kept: it means awake. No session rule yet; that waits for a real night (SRA4).
    fun parseSleep(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<Minute> = minutes(value, 0x11, zone, true)

    /** `38 surface(2) body(2)`, 0.01 °C: the live reading; it carries no time, so [nowMs]. */
    fun parseTemperature(value: ByteArray, nowMs: Long): TempSample? =
        if (isFrame(value, 0x38) && u16(value, 1) > 0) TempSample(nowMs, u16(value, 1) / 100f) else null

    /** `39 ts(4)` + 3 × (surface u16, body u16), one pair per 5 min; 0 = no reading. Layout from the vendor app. */
    fun parseTemperatureHistory(value: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<TempSample> {
        if (!isFrame(value, 0x39)) return emptyList()
        val ts = u32(value, 1)
        return (0..2).mapNotNull { i ->
            val c = u16(value, 5 + 4 * i)
            if (c > 0) TempSample(ringMs(ts + 300 * i, zone), c / 100f) else null
        }
    }

    // ── Thinning (PLAN17 section 4) ───────────────────────────────────────

    // Finished slots only: the stores keep the larger value per timestamp, so a half-filled slot's mean could stick.
    fun hrPer5Min(samples: List<HrSample>, nowMs: Long): List<HrSample> =
        samples.filter { it.bpm > 0 }.groupBy { it.timestampMs - it.timestampMs % 300_000 }
            .filterKeys { it + 300_000 <= nowMs }
            .map { (slot, s) -> HrSample(slot, s.map { it.bpm }.average().roundToInt()) }
            .sortedBy { it.timestampMs }

    private fun hourMs(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    // The ring has no calories or distance per hour: 0 here, and the sync sends neither.
    fun stepsPerHour(minutes: List<Minute>, zone: ZoneId = ZoneId.systemDefault()): List<ActivitySample> =
        minutes.groupBy { hourMs(it.timestampMs, zone) }
            .map { (hour, m) -> ActivitySample(hour, m.sumOf { it.value }, 0, 0) }
            .sortedBy { it.timestampMs }

    fun spo2PerHour(minutes: List<Minute>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Spo2Sample> =
        minutes.filter { it.value > 0 }.groupBy { hourMs(it.timestampMs, zone) }
            .filterKeys { it + 3_600_000 <= nowMs }
            .map { (hour, m) -> Spo2Sample(hour, m.map { it.value }.average().roundToInt(), m.minOf { it.value }) }
            .sortedBy { it.timestampMs }
}
