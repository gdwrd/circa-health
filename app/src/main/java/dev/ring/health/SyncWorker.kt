package dev.ring.health

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
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
import dev.ring.health.Prefs.lastActivityMs
import dev.ring.health.Prefs.lastHrMs
import dev.ring.health.Prefs.lastHrvMs
import dev.ring.health.Prefs.lastSleepMs
import dev.ring.health.Prefs.sleepSchema
import dev.ring.health.Prefs.syncIntervalMin
import dev.ring.health.Prefs.lastSpo2Ms
import dev.ring.health.Prefs.lastStressMs
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.ui.Api
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
        setForeground(buildForegroundInfo("Syncing ring…"))
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
            Log.w(TAG, "No VictoriaMetrics URL configured")
            log("Skipped: no server URL in Settings")
            return Result.failure()
        }

        val conn = ColmiConnection(applicationContext, address)
        return try {
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

            // ── Heart rate — today + yesterday ────────────────────────────
            val hrSamples = mutableListOf<HrSample>()
            for (daysBack in 0..1) {
                val day = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -daysBack)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                conn.writePacket(ColmiProtocol.hrRequest(day))
                hrSamples += collectHr(conn, day)
            }

            // ── HRV — today + yesterday ───────────────────────────────────
            val hrvSamples = mutableListOf<HrvSample>()
            for (daysAgo in 0..1) {
                val day = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -daysAgo)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                conn.writePacket(ColmiProtocol.hrvRequest(daysAgo))
                hrvSamples += collectHrv(conn, day)
            }

            // ── Activity (steps/calories/distance) — today + yesterday ────
            val activitySamples = mutableListOf<ActivitySample>()
            for (daysAgo in 0..1) {
                conn.writePacket(ColmiProtocol.activityRequest(daysAgo))
                activitySamples += collectActivity(conn)
            }

            // ── Stress — today ────────────────────────────────────────────
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            conn.writePacket(ColmiProtocol.stressRequest())
            val stressSamples = collectStress(conn, today)

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

            if (metrics.isEmpty()) {
                Log.i(TAG, "No new metrics to push")
                log("Server: nothing new to push")
            } else {
                val pusher = MetricsPusher(vmUrl, applicationContext.apiToken)
                val pushed = pusher.push(metrics)
                log("Server: ${if (pushed) "pushed" else "push FAILED for"} ${metrics.size} metrics to $vmUrl (${pusher.lastResult})")
                if (!pushed) {
                    scheduleNext(applicationContext)
                    return Result.success()
                }

                // Advance cursors
                hrSamples.maxOfOrNull { it.timestampMs }
                    ?.let { applicationContext.lastHrMs = it }
                hrvSamples.maxOfOrNull { it.timestampMs }
                    ?.let { applicationContext.lastHrvMs = it }
                activitySamples.maxOfOrNull { it.timestampMs }
                    ?.let { applicationContext.lastActivityMs = it }
                stressSamples.maxOfOrNull { it.timestampMs }
                    ?.let { applicationContext.lastStressMs = it }
                spo2Samples.maxOfOrNull { it.timestampMs }
                    ?.let { applicationContext.lastSpo2Ms = it }
                sleepSessions.maxOfOrNull { it.startMs }
                    ?.let { applicationContext.lastSleepMs = it }
                applicationContext.sleepSchema = 3

                runCatching { postPending() }.onSuccess { n -> if (n > 0) log("Server: showed $n notifications") }
            }

            applicationContext.lastSyncMs = System.currentTimeMillis()
            Log.i(TAG, "Sync complete, pushed ${metrics.size} metrics")
            scheduleNext(applicationContext)
            Result.success()

        } catch (e: Exception) {
            log("Sync error: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn.disconnect()
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

        val lastHrMs       = ctx.lastHrMs
        val lastHrvMs      = ctx.lastHrvMs
        val lastActivityMs = ctx.lastActivityMs
        val lastStressMs   = ctx.lastStressMs
        val lastSpo2Ms     = ctx.lastSpo2Ms
        // Once after an upgrade that changed sleep parsing: re-send the sessions the ring still holds.
        val lastSleepMs    = if (ctx.sleepSchema < 3) 0L else ctx.lastSleepMs

        // ── Battery (always push — instantaneous reading) ─────────────────
        if (battery != null) {
            metrics += Metric("ring_battery_pct",  labels, battery.level.toDouble(), nowMs)
            metrics += Metric("ring_charging",     labels, if (battery.charging) 1.0 else 0.0, nowMs)
        }

        // ── Heart rate: raw 5-min samples (ring's native granularity) ─────
        // Aggregations like RHR (overnight min) are computed in Grafana.
        for (s in hrSamples.filter { it.timestampMs > lastHrMs && it.bpm > 0 }) {
            metrics += Metric("biometric_hr_bpm", labels, s.bpm.toDouble(), s.timestampMs)
        }

        // ── HRV: raw 30-min samples (ring's native granularity) ───────────
        for (s in hrvSamples.filter { it.timestampMs > lastHrvMs && it.valueMs > 0 }) {
            metrics += Metric("biometric_hrv_rmssd", labels, s.valueMs.toDouble(), s.timestampMs)
        }

        // ── Activity: steps/calories/distance per hourly slot ─────────────
        for (s in activitySamples.filter { it.timestampMs > lastActivityMs }) {
            metrics += Metric("biometric_steps",            labels, s.steps.toDouble(),          s.timestampMs)
            metrics += Metric("biometric_calories",         labels, s.calories.toDouble(),        s.timestampMs)
            metrics += Metric("biometric_distance_meters",  labels, s.distanceMeters.toDouble(),  s.timestampMs)
        }

        // ── Stress: level per 30-min slot ────────────────────────────────
        for (s in stressSamples.filter { it.timestampMs > lastStressMs }) {
            metrics += Metric("biometric_stress", labels, s.level.toDouble(), s.timestampMs)
        }

        // ── SpO2: hourly average ──────────────────────────────────────────
        for (s in spo2Samples.filter { it.timestampMs > lastSpo2Ms }) {
            metrics += Metric("biometric_spo2_pct", labels, s.pct.toDouble(), s.timestampMs)
        }

        // ── Sleep: total + stage breakdown for every new session ─────────
        val newSessions = sessions.filter { it.startMs > lastSleepMs && it.durationMinutes > 0 }
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
        for (n in pending) {
            val o = n.jsonObject
            val id = o["id"]!!.jsonPrimitive.int
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
            Api.ack(ctx, id)
        }
        return pending.size
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
            val minutes = ctx.syncIntervalMin
            if (minutes <= 0) {
                SyncLog.add(ctx, "Background sync is off; nothing scheduled")
                return
            }
            SyncLog.add(ctx, "Next sync in $minutes min")
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
