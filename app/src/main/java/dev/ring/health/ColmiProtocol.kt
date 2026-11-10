package dev.ring.health

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.UUID

// Protocol constants and packet builders derived from Gadgetbridge source:
// service/devices/colmi/ColmiR0xDeviceSupport.java + ColmiR0xConstants.java
object ColmiProtocol {

    // ── Service / characteristic UUIDs ────────────────────────────────────
    val SERVICE_V1: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val SERVICE_V2: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")

    val CHAR_WRITE: UUID     = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_COMMAND: UUID   = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val CHAR_NOTIFY_V1: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_NOTIFY_V2: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── Command bytes ──────────────────────────────────────────────────────
    const val CMD_BATTERY: Byte           = 0x03
    const val CMD_SYNC_HEART_RATE: Byte   = 0x15
    const val CMD_HR_LOG_SETTINGS: Byte   = 0x16
    const val CMD_SYNC_STRESS: Byte       = 0x37
    const val CMD_SYNC_HRV: Byte          = 0x39
    const val CMD_SYNC_ACTIVITY: Byte     = 0x43.toByte()
    const val CMD_MANUAL_HEART_RATE: Byte = 0x69
    const val CMD_BIG_DATA_V2: Byte       = 0xbc.toByte()

    const val BIG_DATA_TYPE_SLEEP: Byte = 0x27
    const val BIG_DATA_TYPE_SPO2: Byte  = 0x2a

    // ── Sleep stage values ────────────────────────────────────────────────
    const val SLEEP_LIGHT: Byte = 0x02
    const val SLEEP_DEEP: Byte  = 0x03
    const val SLEEP_REM: Byte   = 0x04
    const val SLEEP_AWAKE: Byte = 0x05

    // ── Packet framing ────────────────────────────────────────────────────
    // 16-byte packet: content in [0..N], checksum at [15].
    // Checksum = sum of content bytes & 0xff.
    fun buildPacket(contents: ByteArray): ByteArray {
        require(contents.size <= 15) { "Packet content too long: ${contents.size}" }
        val buf = ByteArray(16)
        contents.copyInto(buf)
        var cs = 0
        for (b in contents) cs = (cs + (b.toInt() and 0xff)) and 0xff
        buf[15] = cs.toByte()
        return buf
    }

    // ── Request builders ──────────────────────────────────────────────────

    fun batteryRequest(): ByteArray = buildPacket(byteArrayOf(CMD_BATTERY))

    // HR log settings. The read answers [0x16, 0x01, enabled (1 on, 2 off), interval minutes]; the write is the Gadgetbridge / tahnok form.
    fun hrLogSettingsRequest(): ByteArray = buildPacket(byteArrayOf(CMD_HR_LOG_SETTINGS, 0x01))
    fun hrLogEvery5MinRequest(): ByteArray = buildPacket(byteArrayOf(CMD_HR_LOG_SETTINGS, 0x02, 0x01, 0x05))

    // HR history for a given calendar day.
    // Ring treats its internal clock as local-time-as-UTC, so we adjust.
    fun hrRequest(dayCal: Calendar): ByteArray {
        val localMidnightMs = dayCal.timeInMillis +
                dayCal.timeZone.getOffset(dayCal.timeInMillis)
        val epochSec = (localMidnightMs / 1000).toInt()
        val bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(CMD_SYNC_HEART_RATE)
        bb.putInt(epochSec)
        return buildPacket(bb.array())
    }

    // HRV history: daysAgo=0 for today, 1 for yesterday, etc.
    fun hrvRequest(daysAgo: Int): ByteArray {
        val bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(CMD_SYNC_HRV)
        bb.putInt(daysAgo)
        return buildPacket(bb.array())
    }

    // Activity history: daysAgo=0 for today, 1 for yesterday, etc.
    // Trailing 4 bytes (0x0f, 0x00, 0x5f, 0x01) are fixed parameters from Gadgetbridge.
    fun activityRequest(daysAgo: Int): ByteArray =
        buildPacket(byteArrayOf(CMD_SYNC_ACTIVITY, daysAgo.toByte(), 0x0f, 0x00, 0x5f, 0x01))

    // Stress history, 30-min samples. daysAgo 0 is byte-identical to the old no-argument request (zero padding);
    // whether the ring honours an offset is unknown, so callers compare the reply with today's.
    fun stressRequest(daysAgo: Int = 0): ByteArray = buildPacket(byteArrayOf(CMD_SYNC_STRESS, daysAgo.toByte()))

    // On-demand HR measurement: ring will stream bpm notifications until stop is sent.
    fun liveHrStartRequest(): ByteArray =
        buildPacket(byteArrayOf(CMD_MANUAL_HEART_RATE, 0x01))

    fun liveHrStopRequest(): ByteArray =
        buildPacket(byteArrayOf(CMD_MANUAL_HEART_RATE, 0x02))

    // Sleep history — raw bytes sent to the V2 command characteristic (not buildPacket-framed).
    fun sleepRequest(): ByteArray = byteArrayOf(
        CMD_BIG_DATA_V2, BIG_DATA_TYPE_SLEEP, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte()
    )

    // SpO2 history — sent to the V2 command characteristic.
    fun spo2Request(): ByteArray = byteArrayOf(
        CMD_BIG_DATA_V2, BIG_DATA_TYPE_SPO2, 0x01, 0x00, 0xff.toByte(), 0x00, 0xff.toByte()
    )

    // ── Data classes ──────────────────────────────────────────────────────

    data class HrSample(val timestampMs: Long, val bpm: Int)

    data class HrvSample(val timestampMs: Long, val valueMs: Int)

    data class SleepSession(
        val startMs: Long,
        val endMs: Long,
        val stages: List<SleepStage>
    ) {
        val durationMinutes: Long get() = (endMs - startMs) / 60_000
    }

    data class SleepStage(val startMs: Long, val durationMinutes: Int, val stage: Byte)

    // Battery: level 0–100, charging true/false.
    data class BatteryStatus(val level: Int, val charging: Boolean)

    // Activity: steps/calories/distance for one hourly slot. calories is raw ring units (÷ 100 = kcal, applied by the backend).
    data class ActivitySample(
        val timestampMs: Long,
        val steps: Int,
        val calories: Int,
        val distanceMeters: Int
    )

    // Stress: ring-computed level (0–100) every 30 minutes.
    data class StressSample(val timestampMs: Long, val level: Int)

    // SpO2: hourly average derived from ring min+max (%), and that hour's minimum.
    data class Spo2Sample(val timestampMs: Long, val pct: Int, val min: Int)

    // Live HR: on-demand reading streamed by the ring.
    // error: 0 = OK, 1 = ring not worn correctly, 2 = temporary error, other = unknown.
    data class LiveHrResult(val bpm: Int, val error: Int)

    // ── Parsers ───────────────────────────────────────────────────────────

    fun parseBatteryPacket(value: ByteArray): BatteryStatus? {
        if (value.size < 3 || value[0] != CMD_BATTERY) return null
        return BatteryStatus(
            level    = value[1].toInt() and 0xff,
            charging = value[2].toInt() == 1
        )
    }

    // Packet layout: [0x69, sub, errorCode, bpm, ...].
    fun parseLiveHrPacket(value: ByteArray): LiveHrResult? {
        if (value.size < 4 || value[0] != CMD_MANUAL_HEART_RATE) return null
        val error = value[2].toInt() and 0xff
        val bpm   = value[3].toInt() and 0xff
        return LiveHrResult(bpm, error)
    }

    // Returns null if packet should be ignored (header or empty).
    // Returns samples (possibly empty list) if it's a data packet.
    fun parseHrPacket(value: ByteArray, dayCal: Calendar): List<HrSample>? {
        if (value.isEmpty() || value[0] != CMD_SYNC_HEART_RATE) return null
        val packetNr = value[1].toInt() and 0xff
        if (packetNr == 0xff) return emptyList() // empty history
        if (packetNr == 0) return null           // header packet only

        val startIndex: Int
        val minutesOffset: Int
        if (packetNr == 1) {
            startIndex = 6  // bytes 2-5 = sync timestamp (we ignore it)
            minutesOffset = 0
        } else {
            startIndex = 2
            minutesOffset = 9 * 5 + (packetNr - 2) * 13 * 5
        }

        val samples = mutableListOf<HrSample>()
        for (i in startIndex until value.size - 1) {
            val bpm = value[i].toInt() and 0xff
            if (bpm != 0) {
                val minuteOfDay = minutesOffset + (i - startIndex) * 5
                val tsMs = slotMs(dayCal, minuteOfDay)
                samples += HrSample(tsMs, bpm)
            }
        }
        return samples
    }

    fun parseHrvPacket(value: ByteArray, dayCal: Calendar): List<HrvSample>? {
        if (value.isEmpty() || value[0] != CMD_SYNC_HRV) return null
        val packetNr = value[1].toInt() and 0xff
        if (packetNr == 0xff) return emptyList()
        if (packetNr == 0) return null

        val startIndex: Int
        val minutesOffset: Int
        if (packetNr == 1) {
            startIndex = 3
            minutesOffset = 0
        } else {
            startIndex = 2
            minutesOffset = 12 * 30 + (packetNr - 2) * 13 * 30
        }

        val samples = mutableListOf<HrvSample>()
        for (i in startIndex until value.size - 1) {
            val v = value[i].toInt() and 0xff
            if (v != 0) {
                val minuteOfDay = minutesOffset + (i - startIndex) * 30
                val tsMs = slotMs(dayCal, minuteOfDay)
                samples += HrvSample(tsMs, v)
            }
        }
        return samples
    }

    // Returns null to skip, or an ActivitySample for a data packet.
    // Date is BCD-encoded in the packet itself: e.g. byte 0x26 = year 2026.
    fun parseActivityPacket(value: ByteArray): ActivitySample? {
        if (value.isEmpty() || value[0] != CMD_SYNC_ACTIVITY) return null
        val nr = value[1].toInt() and 0xff
        if (nr == 0xff || nr == 0xf0) return null  // empty or initial packet

        if (value.size < 13) return null

        val year  = 2000 + (bcdByte(value[1]) ?: return null)
        val month = (bcdByte(value[2]) ?: return null) - 1  // Calendar is 0-based
        val day   = bcdByte(value[3]) ?: return null
        val hour  = (value[4].toInt() and 0xff) / 4

        val cal = Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val calories = leUint16(value, 7)
        val steps    = leUint16(value, 9)
        val distance = leUint16(value, 11)

        return ActivitySample(cal.timeInMillis, steps, calories, distance)
    }

    // Returns parsed stress samples for a single V1 notification packet.
    // dayCal should be midnight of today.
    fun parseStressPacket(value: ByteArray, dayCal: Calendar): List<StressSample> {
        if (value.isEmpty() || value[0] != CMD_SYNC_STRESS) return emptyList()
        val packetNr = value[1].toInt() and 0xff
        if (packetNr == 0xff || packetNr == 0) return emptyList()

        val startIndex = if (packetNr == 1) 3 else 2
        val minutesOffset = if (packetNr == 1) 0
                            else 12 * 30 + (packetNr - 2) * 13 * 30

        val samples = mutableListOf<StressSample>()
        for (i in startIndex until value.size - 1) {
            val v = value[i].toInt() and 0xff
            if (v != 0) {
                val minuteOfDay = minutesOffset + (i - startIndex) * 30
                samples += StressSample(slotMs(dayCal, minuteOfDay), v)
            }
        }
        return samples
    }

    // Parses the BIG_DATA_V2 SpO2 packet: hourly min+max per day, averaged.
    fun parseSpo2Packet(value: ByteArray): List<Spo2Sample> {
        if (value.size < 6) return emptyList()
        if (value[0] != CMD_BIG_DATA_V2 || value[1] != BIG_DATA_TYPE_SPO2) return emptyList()
        val length = leUint16(value, 2)
        val samples = mutableListOf<Spo2Sample>()
        var idx = 6
        // Mirrors Gadgetbridge's loop: process the day fully, then exit if it was today (daysAgo==0).
        var lastDaysAgo = -1
        while (lastDaysAgo != 0 && idx - 6 < length && idx < value.size) {
            val daysAgo = value[idx].toInt() and 0xff
            lastDaysAgo = daysAgo
            idx++
            val dayCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -daysAgo)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            for (hour in 0..23) {
                if (idx + 1 >= value.size || idx - 6 >= length) break
                val min = value[idx].toInt() and 0xff; idx++
                val max = value[idx].toInt() and 0xff; idx++
                if (min > 0 && max > 0) {
                    dayCal.set(Calendar.HOUR_OF_DAY, hour)
                    samples += Spo2Sample(dayCal.timeInMillis, (min + max) / 2, min)
                }
            }
        }
        return samples
    }

    // Returns list of sleep sessions parsed from a single BIG_DATA_V2 sleep packet.
    fun parseSleepPacket(value: ByteArray): List<SleepSession> {
        if (value.size < 7) return emptyList()
        if (value[0] != CMD_BIG_DATA_V2 || value[1] != BIG_DATA_TYPE_SLEEP) return emptyList()
        val length = leUint16(value, 2)
        if (length < 2) return emptyList()

        val daysInPacket = value[6].toInt() and 0xff
        val sessions = mutableListOf<SleepSession>()
        var idx = 7

        repeat(daysInPacket) {
            if (idx + 5 >= value.size) return@repeat
            val daysAgo = value[idx].toInt() and 0xff; idx++
            val dayBytes = value[idx].toInt() and 0xff; idx++
            val sleepStart = leUint16(value, idx); idx += 2
            val sleepEnd   = leUint16(value, idx); idx += 2

            val sessionStartCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -daysAgo)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // sleepStart > sleepEnd means it started before midnight
            if (sleepStart > sleepEnd) {
                sessionStartCal.add(Calendar.MINUTE, sleepStart - 1440)
            } else {
                sessionStartCal.add(Calendar.MINUTE, sleepStart)
            }
            val sessionEndCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -daysAgo)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, sleepEnd)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val startMs = sessionStartCal.timeInMillis
            val endMs   = sessionEndCal.timeInMillis

            val stages = mutableListOf<SleepStage>()
            val stageCal = sessionStartCal.clone() as Calendar
            var j = 4
            while (j < dayBytes && idx + 1 < value.size) {
                val stage    = value[idx]
                val duration = value[idx + 1].toInt() and 0xff
                if (duration > 0) {
                    stages += SleepStage(stageCal.timeInMillis, duration, stage)
                    stageCal.add(Calendar.MINUTE, duration)
                }
                idx += 2
                j   += 2
            }

            sessions += SleepSession(startMs, endMs, stages)
        }
        return sessions
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    // BCD byte: 0x26 → 26 (not 38). Null when a nibble is not a decimal digit.
    private fun bcdByte(b: Byte): Int? {
        val hi = (b.toInt() and 0xf0) shr 4
        val lo = b.toInt() and 0x0f
        return if (hi > 9 || lo > 9) null else hi * 10 + lo
    }

    // Wall-clock hour and minute, not midnight + minutes: that is an hour off after a DST change.
    private fun slotMs(cal: Calendar, minuteOfDay: Int): Long {
        return Calendar.getInstance().apply {
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), minuteOfDay / 60, minuteOfDay % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun leUint16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)
}
