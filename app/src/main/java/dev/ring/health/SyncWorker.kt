package dev.ring.health

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ring.health.ColmiProtocol.ActivitySample
import dev.ring.health.ColmiProtocol.BatteryStatus
import dev.ring.health.ColmiProtocol.HrSample
import dev.ring.health.ColmiProtocol.HrvSample
import dev.ring.health.ColmiProtocol.SLEEP_AWAKE
import dev.ring.health.ColmiProtocol.SLEEP_DEEP
import dev.ring.health.ColmiProtocol.SLEEP_LIGHT
import dev.ring.health.ColmiProtocol.SLEEP_REM
import dev.ring.health.ColmiProtocol.SleepSession
import dev.ring.health.ColmiProtocol.Spo2Sample
import dev.ring.health.ColmiProtocol.StressSample
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.batteryAlerts
import dev.ring.health.Prefs.batteryWarned
import dev.ring.health.Prefs.chargedTold
import dev.ring.health.Prefs.lastBatteryPct
import dev.ring.health.Prefs.lastCharging
import dev.ring.health.Prefs.lastDeepSyncDay
import dev.ring.health.Prefs.lastScreenMs
import dev.ring.health.Prefs.lastSleepMs
import dev.ring.health.Prefs.phoneSensor
import dev.ring.health.Prefs.usageAccess
import dev.ring.health.Prefs.staleWarned
import dev.ring.health.Prefs.sleepSchema
import dev.ring.health.Prefs.syncIntervalMin
import dev.ring.health.Prefs.todayJson
import dev.ring.health.Prefs.todayJsonMs
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.ui.Api
import dev.ring.health.widget.RingWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "SyncWorker"
private const val NOTIF_CHANNEL = "ring_sync"
private const val NOTIF_ID = 1

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val log = { msg: String -> SyncLog.add(applicationContext, msg) }
        log("Sync started")

        val address = applicationContext.ringAddress
        if (address.isNullOrBlank()) {
            Log.w(TAG, "No ring address configured")
            log("Skipped: no ring address in Settings")
            return Result.failure()
        }
        val vmUrl = applicationContext.vmUrl
        if (vmUrl.isBlank()) {
            Log.w(TAG, "No server URL configured")
            log("Skipped: no server URL in Settings")
            return Result.failure()
        }

        if (liveHrActive) {
            log("Skipped: ring busy with live heart rate")
            scheduleNext(applicationContext)
            return Result.success()
        }

        // Deep sync re-reads the 7 days the ring keeps: daily from 04:00, or when the last good push is over 24 h old.
        // Re-sending is safe: VM dedup keeps the larger sample per series+timestamp and slot values only grow.
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val todayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val lastOk = applicationContext.lastSyncMs
        val stale = lastOk == 0L || now - lastOk > 24 * 3_600_000L
        val deep = stale || (todayKey != applicationContext.lastDeepSyncDay && cal.get(Calendar.HOUR_OF_DAY) >= 4)
        if (stale) log(if (lastOk == 0L) "Deep sync (no push yet)" else "Deep sync (last push ${(now - lastOk) / 3_600_000} h ago)")
        else if (deep) log("Deep sync (daily)")
        val days = if (deep) 6 else 1
        val hrN = IntArray(days + 1); val hrvN = IntArray(days + 1); val actN = IntArray(days + 1); val stressN = IntArray(days + 1)

        val conn = ColmiConnection(applicationContext, address)
        return try {
            // Android 12+ refuses a foreground start from a delayed background run unless battery-exempt; sync anyway.
            runCatching { setForeground(buildForegroundInfo("Syncing ring…")) }
                .onFailure { if (it is CancellationException) throw it; log("Sync: no foreground") }
            val connected = conn.connect()
            if (!connected) {
                Log.w(TAG, "Could not connect to ring")
                log("Ring: could not connect")
                scheduleNext(applicationContext)
                return Result.success()
            }

            // ── Battery ───────────────────────────────────────────────────
            conn.writePacket(ColmiProtocol.batteryRequest())
            val battery = collectBattery(conn)
            if (battery != null) onBattery(battery)

            // ── Hardware / firmware version ───────────────────────────────
            // Tells the ring variants apart (R02_V3.0, RY02_V3.0, RT02R_V3.1 ...); logged once per app start.
            if (!ringVersionLogged) {
                val hw = conn.readString(ColmiProtocol.CHAR_HARDWARE_REV)
                val fw = conn.readString(ColmiProtocol.CHAR_FIRMWARE_REV)
                if (hw != null || fw != null) {
                    ringVersionLogged = true
                    log("Ring: hardware ${hw ?: "unknown"}, firmware ${fw ?: "unknown"}")
                }
            }

            // ── HR log interval ───────────────────────────────────────────
            // The ring ships logging one reading per 15 min; the backend's workout detection needs one per 5 min.
            conn.writePacket(ColmiProtocol.hrLogSettingsRequest())
            val hrLog = withTimeoutOrNull(5_000) {
                var p: ByteArray
                do p = conn.notifyV1.receive() while (p.size < 4 || p[0] != ColmiProtocol.CMD_HR_LOG_SETTINGS || p[1].toInt() != 1)
                p
            }
            if (hrLog != null && (hrLog[2].toInt() != 1 || hrLog[3].toInt() != 5)) {
                conn.writePacket(ColmiProtocol.hrLogEvery5MinRequest())
                log("Ring: heart rate log set to every 5 min (was ${if (hrLog[2].toInt() == 1) "every ${hrLog[3].toInt() and 0xff} min" else "off"})")
            }

            // ── Heart rate — today + yesterday (deep: 7 days) ─────────────
            val hrSamples = mutableListOf<HrSample>()
            for (daysBack in 0..days) {
                val day = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -daysBack)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                conn.writePacket(ColmiProtocol.hrRequest(day))
                val got = collectHr(conn, day)
                hrN[daysBack] = got.size
                hrSamples += got
            }

            // ── HRV — today + yesterday (deep: 7 days) ────────────────────
            val hrvSamples = mutableListOf<HrvSample>()
            for (daysAgo in 0..days) {
                val day = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -daysAgo)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                conn.writePacket(ColmiProtocol.hrvRequest(daysAgo))
                val got = collectHrv(conn, day)
                hrvN[daysAgo] = got.size
                hrvSamples += got
            }

            // ── Activity (steps/calories/distance) — today + yesterday (deep: 7 days)
            val activitySamples = mutableListOf<ActivitySample>()
            for (daysAgo in 0..days) {
                conn.writePacket(ColmiProtocol.activityRequest(daysAgo))
                val got = collectActivity(conn)
                actN[daysAgo] = got.size
                activitySamples += got
            }

            // ── Stress — today (deep: days 1..6 too, if the ring honours the offset) ──
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            conn.writePacket(ColmiProtocol.stressRequest())
            val stressSamples = collectStress(conn, today).toMutableList()
            stressN[0] = stressSamples.size
            if (deep) {
                val todayLevels = stressSamples.map { it.level }
                for (daysAgo in 1..days) {
                    val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -daysAgo) }
                    conn.writePacket(ColmiProtocol.stressRequest(daysAgo))
                    val got = collectStress(conn, day)
                    // Same values as today's reply = the ring ignored the offset; keeping them would copy today onto d-N.
                    if (got.isNotEmpty() && got.map { it.level } == todayLevels) {
                        log("Deep sync: stress d-$daysAgo echoes today (${got.size})")
                    } else {
                        stressN[daysAgo] = got.size
                        stressSamples += got
                    }
                }
                for (d in 0..days) log("Deep sync: d-$d hr ${hrN[d]} · hrv ${hrvN[d]} · act ${actN[d]} · stress ${stressN[d]}")
            }

            // ── SpO2 ──────────────────────────────────────────────────────
            conn.writeCommand(ColmiProtocol.spo2Request())
            val spo2Samples = collectSpo2(conn)

            // ── Sleep ─────────────────────────────────────────────────────
            conn.writeCommand(ColmiProtocol.sleepRequest())
            val sleepSessions = collectSleep(conn)

            log(
                "Ring: read ${hrSamples.size} HR, ${hrvSamples.size} HRV, ${activitySamples.size} activity, " +
                    "${stressSamples.size} stress, ${spo2Samples.size} SpO2, ${sleepSessions.size} sleep sessions" +
                    (battery?.let { ", battery ${it.level}%" } ?: "")
            )

            // ── Compute + push metrics ────────────────────────────────────
            val metrics = buildMetrics(
                applicationContext,
                battery, hrSamples, hrvSamples,
                activitySamples, stressSamples, spo2Samples, sleepSessions
            )

            // Everything read is kept on the phone; every unsent row goes out, so a failed push is retried with no age limit.
            val store = SampleStore.get(applicationContext)
            val ctx = applicationContext
            val now = System.currentTimeMillis()
            val phone = ctx.phoneSensor && ctx.usageAccess
            val screen = if (phone) screenMetrics(ctx, if (ctx.lastScreenMs == 0L) now - 7 * 86_400_000L else ctx.lastScreenMs, now) else emptyList()
            store.add(metrics + screen)
            if (phone) ctx.lastScreenMs = screen.maxOfOrNull { it.timestampMs } ?: now
            val pusher = MetricsPusher(vmUrl, applicationContext.apiToken)
            var sent = 0
            while (true) {
                val chunk = store.unsent(5000)
                if (chunk.isEmpty()) break
                if (!pusher.push(chunk)) {
                    log("Server: push FAILED for ${chunk.size} samples to $vmUrl (${pusher.lastResult}), ${store.stats().third} waiting")
                    scheduleNext(applicationContext)
                    return Result.success()
                }
                store.markSent(chunk)
                sent += chunk.size
            }
            if (sent == 0) {
                Log.i(TAG, "No new metrics to push")
                log("Server: nothing new to push")
            } else {
                log("Server: pushed $sent samples to $vmUrl (${pusher.lastResult})")
                runCatching { postPending() }.onSuccess { n -> if (n > 0) log("Server: showed $n notifications") }
            }
            if (metrics.isNotEmpty()) {
                sleepSessions.maxOfOrNull { it.endMs }
                    ?.let { applicationContext.lastSleepMs = it }
                applicationContext.sleepSchema = 4
            }

            // Widget snapshot (a failed push returned above); plain VM answers /today 404 (null), so the widget keeps "Open to sync".
            runCatching {
                Api.today(applicationContext)?.let {
                    applicationContext.todayJson = it.toString()
                    applicationContext.todayJsonMs = System.currentTimeMillis()
                }
                RingWidget().updateAll(applicationContext)
            }.onFailure { log("Widget: ${it.javaClass.simpleName}: ${it.message}") }

            applicationContext.lastSyncMs = System.currentTimeMillis()
            applicationContext.staleWarned = false
            if (deep) applicationContext.lastDeepSyncDay = todayKey
            Log.i(TAG, "Sync complete, pushed $sent samples")
            scheduleNext(applicationContext)
            Result.success()

        } catch (e: Exception) {
            if (e is CancellationException) throw e // a REPLACE enqueue cancelled this run; rescheduling would replace the new request
            log("Sync error: ${e.javaClass.simpleName}: ${e.message}")
            scheduleNext(applicationContext)
            Result.success()
        } finally {
            conn.disconnect()
            runCatching { warnIfStale() } // every attempt incl. "could not connect", which is the case it exists for
        }
    }

    // ── Collection helpers ─────────────────────────────────────────────────

    private suspend fun collectBattery(conn: ColmiConnection): BatteryStatus? {
        return withTimeoutOrNull(10_000) {
            while (true) {
                val pkt = conn.notifyV1.receive()
                if (pkt.size >= 3 && pkt[0] == ColmiProtocol.CMD_BATTERY) {
                    return@withTimeoutOrNull ColmiProtocol.parseBatteryPacket(pkt)
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    private suspend fun collectHr(conn: ColmiConnection, day: Calendar): List<HrSample> {
        val samples = mutableListOf<HrSample>()
        var totalPackets = -1
        withTimeoutOrNull(15_000) {
            while (true) {
                val pkt = conn.notifyV1.receive()
                if (pkt.isEmpty() || pkt[0] != ColmiProtocol.CMD_SYNC_HEART_RATE) continue
                val nr = pkt[1].toInt() and 0xff
                if (nr == 0xff) break
                if (nr == 0) { totalPackets = pkt[2].toInt() and 0xff; continue }
                val parsed = ColmiProtocol.parseHrPacket(pkt, day) ?: continue
                samples += parsed
                if (totalPackets > 0 && nr == totalPackets - 1) break
            }
        }
        return samples
    }

    private suspend fun collectHrv(conn: ColmiConnection, day: Calendar): List<HrvSample> {
        val samples = mutableListOf<HrvSample>()
        withTimeoutOrNull(15_000) {
            while (true) {
                val pkt = conn.notifyV1.receive()
                if (pkt.isEmpty() || pkt[0] != ColmiProtocol.CMD_SYNC_HRV) continue
                val nr = pkt[1].toInt() and 0xff
                if (nr == 0xff) break
                if (nr == 0) continue
                val parsed = ColmiProtocol.parseHrvPacket(pkt, day) ?: continue
                samples += parsed
                if (nr == 4) break
            }
        }
        return samples
    }

    private suspend fun collectActivity(conn: ColmiConnection): List<ActivitySample> {
        val samples = mutableListOf<ActivitySample>()
        withTimeoutOrNull(20_000) {
            while (true) {
                val pkt = conn.notifyV1.receive()
                if (pkt.isEmpty() || pkt[0] != ColmiProtocol.CMD_SYNC_ACTIVITY) continue
                val nr = pkt[1].toInt() and 0xff
                if (nr == 0xff) break  // empty history
                if (nr == 0xf0) continue  // initial/header packet
                val parsed = ColmiProtocol.parseActivityPacket(pkt)
                if (parsed != null) samples += parsed
                if (pkt.size > 6) {
                    val current = pkt[5].toInt() and 0xff
                    val total   = pkt[6].toInt() and 0xff
                    if (total > 0 && current == total - 1) break
                }
            }
        }
        return samples
    }

    private suspend fun collectStress(conn: ColmiConnection, today: Calendar): List<StressSample> {
        val samples = mutableListOf<StressSample>()
        withTimeoutOrNull(15_000) {
            while (true) {
                val pkt = conn.notifyV1.receive()
                if (pkt.isEmpty() || pkt[0] != ColmiProtocol.CMD_SYNC_STRESS) continue
                val nr = pkt[1].toInt() and 0xff
                if (nr == 0xff) break
                if (nr == 0) continue
                samples += ColmiProtocol.parseStressPacket(pkt, today)
                if (nr == 4) break
            }
        }
        return samples
    }

    private suspend fun collectSpo2(conn: ColmiConnection): List<Spo2Sample> =
        collectBigData(conn, ColmiProtocol.BIG_DATA_TYPE_SPO2, "SpO2")?.let(ColmiProtocol::parseSpo2Packet) ?: emptyList()

    /**
     * Big-data V2 replies (sleep, SpO2) are longer than one BLE notification (20 bytes at the default MTU):
     * a 6-byte header carrying the payload length at bytes 2..3, then the payload split over several
     * notifications. Reassemble them; parsing only the first notification truncated the night after 3 stages.
     */
    private suspend fun collectBigData(conn: ColmiConnection, type: Byte, what: String): ByteArray? {
        var buf: ByteArray? = null
        var need = Int.MAX_VALUE
        withTimeoutOrNull(20_000) {
            while ((buf?.size ?: 0) < need) {
                val pkt = conn.notifyV2.receive()
                val b = buf
                if (b == null) {
                    if (pkt.size < 6 || pkt[0] != ColmiProtocol.CMD_BIG_DATA_V2 || pkt[1] != type) continue
                    need = 6 + ((pkt[2].toInt() and 0xff) or ((pkt[3].toInt() and 0xff) shl 8))
                    buf = pkt
                } else {
                    buf = b + pkt
                }
            }
        }
        val b = buf ?: return null
        if (b.size < need) SyncLog.add(applicationContext, "Ring: $what reply incomplete (${b.size}/$need bytes), parsing what arrived")
        return b.copyOf(minOf(b.size, need))
    }

    private suspend fun collectSleep(conn: ColmiConnection): List<SleepSession> =
        collectBigData(conn, ColmiProtocol.BIG_DATA_TYPE_SLEEP, "sleep")?.let(ColmiProtocol::parseSleepPacket) ?: emptyList()

    // ── Metric computation ─────────────────────────────────────────────────

    /** Phone screen on/off moments (1.0 / 0.0); only times, never app names. */
    private fun screenMetrics(ctx: Context, from: Long, to: Long): List<Metric> {
        val labels = mapOf("device" to "colmi_r02")
        val ev = (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = mutableListOf<Metric>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) out += Metric("biometric_screen_on", labels, 1.0, e.timeStamp)
            else if (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) out += Metric("biometric_screen_on", labels, 0.0, e.timeStamp)
        }
        return out
    }

    private fun buildMetrics(
        ctx: Context,
        battery: BatteryStatus?,
        hrSamples: List<HrSample>,
        hrvSamples: List<HrvSample>,
        activitySamples: List<ActivitySample>,
        stressSamples: List<StressSample>,
        spo2Samples: List<Spo2Sample>,
        sessions: List<SleepSession>
    ): List<Metric> {
        val metrics = mutableListOf<Metric>()
        val labels  = mapOf("device" to "colmi_r02")
        val nowMs   = System.currentTimeMillis()

        // No watermarks for slot data: whole days are re-sent and VM dedup keeps the larger sample.
        // Once after an upgrade that changed sleep parsing: re-send the sessions the ring still holds.
        val lastSleepMs    = if (ctx.sleepSchema < 4) 0L else ctx.lastSleepMs

        // ── Battery (always push — instantaneous reading) ─────────────────
        if (battery != null) {
            metrics += Metric("ring_battery_pct",  labels, battery.level.toDouble(), nowMs)
            metrics += Metric("ring_charging",     labels, if (battery.charging) 1.0 else 0.0, nowMs)
        }

        // ── Heart rate: raw 5-min samples (ring's native granularity) ─────
        // Aggregations like RHR (overnight min) are computed in PromQL / the backend.
        for (s in hrSamples.filter { it.bpm > 0 }) {
            metrics += Metric("biometric_hr_bpm", labels, s.bpm.toDouble(), s.timestampMs)
        }

        // ── HRV: raw 30-min samples (ring's native granularity) ───────────
        for (s in hrvSamples.filter { it.valueMs > 0 }) {
            metrics += Metric("biometric_hrv_rmssd", labels, s.valueMs.toDouble(), s.timestampMs)
        }

        // ── Activity: steps/calories/distance per hourly slot ─────────────
        for (s in activitySamples) {
            metrics += Metric("biometric_steps",            labels, s.steps.toDouble(),          s.timestampMs)
            metrics += Metric("biometric_calories",         labels, s.calories.toDouble(),        s.timestampMs)
            metrics += Metric("biometric_distance_meters",  labels, s.distanceMeters.toDouble(),  s.timestampMs)
        }

        // ── Stress: level per 30-min slot ────────────────────────────────
        for (s in stressSamples) {
            metrics += Metric("biometric_stress", labels, s.level.toDouble(), s.timestampMs)
        }

        // ── SpO2: hourly average + minimum ────────────────────────────────
        for (s in spo2Samples) {
            metrics += Metric("biometric_spo2_pct", labels, s.pct.toDouble(), s.timestampMs)
            // Finished hours only: the backend keeps the larger value per timestamp, so a part-hour's higher minimum would stick.
            if (s.timestampMs + 3_600_000 <= nowMs) metrics += Metric("biometric_spo2_min_pct", labels, s.min.toDouble(), s.timestampMs)
        }

        // ── Sleep: total + stage breakdown for every new or grown session ──
        // Watermark is the session END: the ring extends tonight's record (same start) while you sleep,
        // and VM dedup keeps the larger sample, so re-sending the grown session replaces the stub.
        val newSessions = sessions.filter { it.endMs > lastSleepMs && it.durationMinutes > 0 }
        for (s in newSessions) {
            metrics += Metric("biometric_sleep_total_min", labels, s.durationMinutes.toDouble(), s.startMs)

            val deepMin  = s.stages.filter { it.stage == SLEEP_DEEP  }.sumOf { it.durationMinutes }
            val remMin   = s.stages.filter { it.stage == SLEEP_REM   }.sumOf { it.durationMinutes }
            val lightMin = s.stages.filter { it.stage == SLEEP_LIGHT }.sumOf { it.durationMinutes }
            if (deepMin  > 0) metrics += Metric("biometric_sleep_deep_min",  labels, deepMin.toDouble(),  s.startMs)
            if (remMin   > 0) metrics += Metric("biometric_sleep_rem_min",   labels, remMin.toDouble(),   s.startMs)
            if (lightMin > 0) metrics += Metric("biometric_sleep_light_min", labels, lightMin.toDouble(), s.startMs)

            val awakeMin = s.stages.filter { it.stage == SLEEP_AWAKE }.sumOf { it.durationMinutes }
            metrics += Metric("biometric_sleep_awake_min", labels, awakeMin.toDouble(), s.startMs)
            metrics += Metric("biometric_sleep_end_ts", labels, s.endMs / 1000.0, s.startMs)
            for (seg in s.stages) {
                val v = when (seg.stage) { SLEEP_LIGHT -> 1.0; SLEEP_DEEP -> 2.0; SLEEP_REM -> 3.0; SLEEP_AWAKE -> 4.0; else -> continue }
                metrics += Metric("biometric_sleep_stage", labels, v, seg.startMs)
            }
            metrics += Metric("biometric_sleep_stage", labels, 0.0, s.endMs)
        }

        return metrics
    }

    // ── Backend notifications ──────────────────────────────────────────────

    /** Silent no-op without a backend (404 -> null). Unposted (no permission) stay pending. */
    @SuppressLint("MissingPermission")
    private suspend fun postPending(): Int {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return 0
        val pending = Api.notifications(ctx) as? JsonArray ?: return 0
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("health", "Health", NotificationManager.IMPORTANCE_DEFAULT))
        var shown = 0
        for (n in pending) {
            val o = n.jsonObject
            val id = o["id"]!!.jsonPrimitive.int
            if (!Api.ack(ctx, id)) continue // ack first: a failed ack would re-alert on every sync
            val intent = Intent(ctx, MainActivity::class.java)
            (o["extra"] as? JsonObject)?.forEach { (k, v) -> intent.putExtra(k, v.jsonPrimitive.content) }
            // request code = id: each notification keeps its own extras
            val open = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notif = NotificationCompat.Builder(ctx, "health")
                .setSmallIcon(R.drawable.ic_stat_circa)
                .setContentTitle(o["title"]!!.jsonPrimitive.content)
                .setContentText(o["body"]!!.jsonPrimitive.content)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1000 + id, notif) // offset keeps clear of the sync notification id
            shown++
        }
        return shown
    }

    /** Stores the level and posts "battery low" / "charged" once per discharge / charge. Skipped without permission. */
    @SuppressLint("MissingPermission")
    private fun onBattery(b: BatteryStatus) {
        val ctx = applicationContext
        ctx.lastBatteryPct = b.level
        ctx.lastCharging = b.charging
        val low = b.level <= 20 && !b.charging
        val full = b.charging && b.level == 100
        if (!low && ctx.batteryWarned) ctx.batteryWarned = false
        if (!b.charging) ctx.chargedTold = false
        if (!ctx.batteryAlerts || !canNotify()) return
        if (low && !ctx.batteryWarned) { post(2, "Ring battery ${b.level} %", "Charge the ring soon."); ctx.batteryWarned = true }
        if (full && !ctx.chargedTold) { post(3, "Ring charged", "Put it back on to keep tracking."); ctx.chargedTold = true }
    }

    /** "Ring not synced for N days" once per gap over 48 h; the ring keeps 7 days, so this is the last warning before loss. */
    private fun warnIfStale() {
        val ctx = applicationContext
        val gap = System.currentTimeMillis() - ctx.lastSyncMs
        if (ctx.lastSyncMs == 0L || gap <= 48 * 3_600_000L || ctx.staleWarned || !canNotify()) return
        post(4, "Ring not synced for ${gap / 86_400_000} days", "Data older than a week is lost.")
        ctx.staleWarned = true
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts on the "health" channel; callers check [canNotify] first. */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, title: String, text: String) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("health", "Health", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, NotificationCompat.Builder(ctx, "health")
            .setSmallIcon(R.drawable.ic_stat_circa).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    // ── Foreground notification ────────────────────────────────────────────

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // unguarded: re-creating renames the channel left by v2.x ("Ring sync")
        nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL, "Circa sync", NotificationManager.IMPORTANCE_LOW))
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_circa)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        /** True while runLiveHr (Measure now, Breathe) holds the ring; a sync then skips its turn. */
        @Volatile internal var liveHrActive = false
        @Volatile private var ringVersionLogged = false

        // Kick off the first sync immediately; each run re-schedules the next one.
        // PeriodicWorkRequest has a hard 15-min OS floor, so we use self-chaining
        // OneTimeWorkRequests with an initial delay instead.
        fun schedule(ctx: Context) {
            if (ctx.syncIntervalMin <= 0) return
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork("ring_sync_periodic")
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(
                "ring_sync",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }

        internal fun scheduleNext(ctx: Context) {
            val user = ctx.syncIntervalMin
            if (user <= 0) {
                SyncLog.add(ctx, "Background sync is off; nothing scheduled")
                return
            }
            val minutes = when {
                ctx.lastCharging -> maxOf(user, 30)
                ctx.lastBatteryPct in 0..20 -> maxOf(user, 15)
                else -> user
            }
            val why = if (minutes == user) "" else if (ctx.lastCharging) " (charging)" else " (battery ${ctx.lastBatteryPct} %)"
            SyncLog.add(ctx, "Next sync in $minutes min$why")
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "ring_sync",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Applies a changed interval: drops the pending run, then (unless off) syncs now and re-chains. */
        fun reschedule(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("ring_sync")
            if (ctx.syncIntervalMin > 0) runNow(ctx) else SyncLog.add(ctx, "Background sync turned off")
        }

        fun runNow(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "ring_sync",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
