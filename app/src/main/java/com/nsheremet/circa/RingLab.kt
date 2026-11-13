package com.nsheremet.circa

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Ring lab: full-rate motion recording and the firmware updater.
// Both were worked out against the emulated firmware in emu/ (ringemu.py); see emu/patch_fw.py for the patch.

/** CRC-16/MODBUS over [data]; 0xFFFF for empty input, as the update protocol expects. */
fun crc16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (b in data) {
        crc = crc xor (b.toInt() and 0xff)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
    }
    return crc
}

/** One update packet: `BC type len(2) crc16(2) payload`, little-endian. */
fun updatePacket(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val crc = crc16(payload)
    return byteArrayOf(0xBC.toByte(), type.toByte(), payload.size.toByte(), (payload.size shr 8).toByte(), crc.toByte(), (crc shr 8).toByte()) + payload
}

/** A Colmi RY02 update file: an 80-byte wrapper (lengths, byte sum, version texts), then the Realtek app image. */
class FirmwareFile(val bytes: ByteArray) {
    private fun le32(o: Int) = (0..3).sumOf { (bytes[o + it].toLong() and 0xff) shl (8 * it) }
    private fun le16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
    private fun text(o: Int) = String(bytes, o, 32, Charsets.US_ASCII).substringBefore('\u0000')

    /** Why this is not an installable RY02 image; null when every field agrees. */
    val problem: String? = when {
        bytes.size < WRAP + HEADER || bytes.size > 400_000 -> "Not an update file (wrong size)"
        le32(4) != (bytes.size - WRAP).toLong() || le32(8) != le32(4) -> "Length fields do not match the file"
        le32(0x0C) != bytes.drop(WRAP).sumOf { (it.toInt() and 0xff).toLong() } and 0xFFFFFFFFL -> "Byte sum does not match (file damaged?)"
        le16(WRAP + 4) != 0x2793 -> "Not an RY02 app image"
        le32(WRAP + 8) != (bytes.size - WRAP - HEADER).toLong() -> "Image length does not match the file"
        else -> null
    }
    val version: String = if (bytes.size >= WRAP) text(0x10) else ""
    val hardware: String = if (bytes.size >= WRAP) text(0x30) else ""

    /** True when the image is already marked ready, which only a modified file is (vendor files leave that to the ring). */
    val modified: Boolean = problem == null && le16(WRAP + 2) and 0x80 == 0

    private companion object { const val WRAP = 0x50; const val HEADER = 0x400 }
}

data class RingInfo(val hardware: String?, val firmware: String?, val battery: ColmiProtocol.BatteryStatus?)

private suspend fun ringInfo(conn: ColmiConnection): RingInfo {
    val hw = conn.readString(ColmiProtocol.CHAR_HARDWARE_REV)
    val fw = conn.readString(ColmiProtocol.CHAR_FIRMWARE_REV)
    conn.writePacket(ColmiProtocol.batteryRequest())
    val battery = withTimeoutOrNull(5_000) {
        var b: ColmiProtocol.BatteryStatus?
        do b = ColmiProtocol.parseBatteryPacket(conn.notifyV1.receive()) while (b == null)
        b
    }
    return RingInfo(hw, fw, battery)
}

/** Connects, reads the ring's versions and battery, disconnects; null when the ring does not answer. */
suspend fun readRingInfo(ctx: Context, address: String): RingInfo? {
    val conn = ColmiConnection(ctx, address)
    SyncWorker.liveHrActive = true // keeps a background sync off the ring
    return try {
        if (conn.connect()) ringInfo(conn) else null
    } catch (t: Throwable) {
        null
    } finally {
        conn.disconnect()
        SyncWorker.liveHrActive = false
    }
}

/**
 * Sends [fw] to the ring the way the public web flasher does: start, announce (length, CRC, byte sum), 1 KB parts,
 * finish, apply. The ring answers each step; it restarts about a second after "apply".
 * Returns null when the ring took the whole file, else what went wrong. Until "apply" the ring keeps its old firmware.
 */
suspend fun flashFirmware(ctx: Context, address: String, fw: FirmwareFile, onProgress: (String) -> Unit): String? {
    fw.problem?.let { return it }
    val conn = ColmiConnection(ctx, address)
    SyncWorker.liveHrActive = true // a background sync mid-update would mix its packets into ours
    return try {
        onProgress("Connecting…")
        if (!conn.connect()) return "Could not connect to the ring"
        val info = ringInfo(conn)
        if (info.hardware != fw.hardware) return "This file is for ${fw.hardware}, the ring is ${info.hardware ?: "unknown"}"
        // A patched image keeps the version text of the build it was cut from, and its patch only fits that build.
        if (fw.modified && info.firmware != fw.version) return "This patch is for ${fw.version}, the ring runs ${info.firmware ?: "unknown"}"
        val battery = info.battery ?: return "Could not read the ring's battery"
        if (battery.level < 30 && !battery.charging) return "Ring battery is ${battery.level}%: charge it to 30% first"

        val chunk = minOf(200, conn.requestMtu(247) - 3) // the web flasher's 200-byte writes when the link allows
        suspend fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean =
            updatePacket(type, payload).asList().chunked(chunk).all { conn.writeCommand(it.toByteArray()) }
        suspend fun answered(type: Int): Boolean {
            val p = withTimeoutOrNull(20_000) {
                var p: ByteArray
                do p = conn.notifyV2.receive() while (p.size < 7 || p[0] != 0xBC.toByte() || p[1].toInt() != type)
                p
            }
            return p != null && p[6].toInt() == 0
        }
        suspend fun step(type: Int, payload: ByteArray = ByteArray(0)) = send(type, payload) && answered(type)

        val image = fw.bytes
        val crc = crc16(image)
        val sum = image.sumOf { it.toInt() and 0xff } and 0xFFFF
        val announce = byteArrayOf(1) + ByteArray(4) { (image.size shr (8 * it)).toByte() } +
            byteArrayOf(crc.toByte(), (crc shr 8).toByte(), sum.toByte(), (sum shr 8).toByte())
        if (!step(1)) return "The ring did not accept the update start"
        if (!step(2, announce)) return "The ring refused the file (size or checksum)"
        val parts = (image.size + 1023) / 1024
        for (i in 0 until parts) {
            if (!currentCoroutineContext().isActive) return "Cancelled"
            onProgress("Sending part ${i + 1} of $parts")
            val part = image.copyOfRange(i * 1024, minOf(image.size, (i + 1) * 1024))
            if (!step(3, byteArrayOf((i + 1).toByte(), ((i + 1) shr 8).toByte()) + part)) return "The ring stopped answering at part ${i + 1} of $parts; its old firmware is untouched"
        }
        if (!step(4)) return "The ring did not confirm the complete file; its old firmware is untouched"
        onProgress("Applying…")
        send(5) // no answer follows: the ring restarts
        SyncLog.add(ctx, "Firmware: sent ${fw.version} (${image.size} bytes${if (fw.modified) ", modified" else ""}) to the ring")
        null
    } catch (t: Throwable) {
        "Update stopped: ${t.message ?: t::class.simpleName}"
    } finally {
        conn.disconnect()
        SyncWorker.liveHrActive = false
    }
}

/** What the wearer was doing during a recording; written into the file name and its first line. */
val MOTION_LABELS = listOf("walk", "run", "ride", "desk", "sleep", "other")

/** `circa-motion-<label>-<yyyyMMdd-HHmm>.csv` in [dir], with `-2`, `-3`… when that name is taken, so no recording is overwritten. */
fun motionFile(dir: File, label: String, time: LocalDateTime): File {
    val base = "circa-motion-$label-" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "$base.csv" else "$base-$it.csv") }.first { !it.exists() }
}

/**
 * Turns the ring's motion-only stream on (`A1 07`; on a ring that answers `A1 FF`, the raw stream `A1 04` instead)
 * and writes every `A1 05` motion sample to [out] as CSV, after a `# label=` line, until cancelled.
 * [onUpdate] gets the sample count, the latest sample and how many stock 1-a-second packets came in.
 * Returns null on a normal stop, else what went wrong.
 */
suspend fun recordMotion(ctx: Context, address: String, out: File, label: String, onUpdate: (Int, ColmiProtocol.MotionSample?, Int) -> Unit): String? {
    val conn = ColmiConnection(ctx, address)
    var started = false
    var rawStream = false // true once the ring refused A1 07 and the A1 04 raw stream runs instead
    SyncWorker.liveHrActive = true
    return try {
        if (!conn.connect()) return "Could not connect to the ring"
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            w.write("# label=$label\n")
            w.write("n,arrival_ms,x_mg,y_mg,z_mg\n") // n counts samples (25 a second on the ring's clock); arrival is the phone's clock
            conn.writePacket(ColmiProtocol.motionOnlyStartRequest())
            started = true
            var n = 0
            var stock = 0
            while (currentCoroutineContext().isActive) {
                val p = conn.notifyV1.receive()
                val samples = ColmiProtocol.parseMotionPacket(p)
                if (samples == null) {
                    if (p.size >= 2 && p[0] == ColmiProtocol.CMD_RAW_STREAM && p[1] == 0xFF.toByte() && !rawStream) {
                        rawStream = true
                        conn.writePacket(ColmiProtocol.rawStreamStartRequest())
                    }
                    if (p.size >= 2 && p[0] == ColmiProtocol.CMD_RAW_STREAM && p[1].toInt() == 3) onUpdate(n, null, ++stock)
                    continue
                }
                val now = System.currentTimeMillis()
                for (s in samples) w.write("${n++},$now,${s.x},${s.y},${s.z}\n")
                onUpdate(n, samples.last(), stock)
            }
        }
        null
    } catch (t: kotlinx.coroutines.CancellationException) {
        null // Stop pressed
    } catch (t: Throwable) {
        "Recording stopped: ${t.message ?: t::class.simpleName}"
    } finally {
        if (started) runCatching {
            // NonCancellable: Stop cancels this coroutine, and a cancelled coroutine cannot suspend to send the stop command.
            withContext(NonCancellable) { withTimeoutOrNull(2_000) {
                conn.writePacket(if (rawStream) ColmiProtocol.rawStreamStopRequest() else ColmiProtocol.motionOnlyStopRequest())
            } }
        }
        conn.disconnect()
        SyncWorker.liveHrActive = false
    }
}

/** Waits for the ring to restart after an update, then reads what it reports. */
suspend fun ringAfterUpdate(ctx: Context, address: String): RingInfo? {
    delay(12_000)
    return readRingInfo(ctx, address)
}
