package com.nsheremet.circa

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.nsheremet.circa.Prefs.carAddress
import com.nsheremet.circa.Prefs.driveWarnedWake
import com.nsheremet.circa.Prefs.todayJson
import org.json.JSONObject
import java.util.Date

/** What the last synced `/today.drive` says at [driveFacts]' `nowMs`: minutes asleep in the last 24 h and minutes since waking. */
class DriveFacts(val wake: Long, val wokeAt: String, val sleepMin: Int, val awakeMin: Int) {
    val asleep get() = "${sleepMin / 60} h" + if (sleepMin % 60 == 0) "" else " ${sleepMin % 60} min"
    val awake get() = if (awakeMin < 60) "$awakeMin min" else "${(awakeMin + 30) / 60} h"
}

private const val FRESH_MIN = 90 // a sync runs every 5 min, so an older snapshot means the ring is not syncing

/** Null without a `drive` snapshot, when it is older than 90 min, or when the ring was off since waking. */
fun driveFacts(ctx: Context, nowMs: Long): DriveFacts? {
    val d = runCatching { JSONObject(ctx.todayJson).optJSONObject("drive") }.getOrNull() ?: return null
    val now = nowMs / 1000.0
    val wake = d.optDouble("wake")
    if (wake.isNaN() || !(now - d.optDouble("as_of") <= FRESH_MIN * 60) || !d.optBoolean("worn")) return null
    // shortcut: sleep_min is as of the snapshot, up to 90 min old; recompute the window on the phone if that ever matters
    return DriveFacts(
        wake.toLong(), DateFormat.getTimeFormat(ctx).format(Date((wake * 1000).toLong())),
        Math.round(d.optDouble("sleep_min", 0.0)).toInt(), ((now - wake) / 60).toInt().coerceAtLeast(0),
    )
}

// Awake 17 to 19 h reacts like 0.05% blood alcohol: Williamson & Feyer, Occup Environ Med 2000. Crash risk against 7 h or more of
// sleep in the last 24 h, under 4 h 11.5 times, 4 to 5 h 4.3 times: Tefft, AAA Foundation for Traffic Safety 2016.
private const val AWAKE_MIN = 18 * 60
private const val SLEEP_VERY_SHORT_MIN = 240
private const val SLEEP_SHORT_MIN = 300
private const val LAB = "In lab tests, people awake for 17 to 19 hours reacted like people at 0.05% blood alcohol."

/** Title and text of the warning these facts earn, or null. 5 to 6 h (1.9 times) is left out: a warning most mornings is a warning ignored. */
fun driveWarning(f: DriveFacts): Pair<String, String>? = when {
    f.awakeMin >= 24 * 60 -> "No sleep recorded for ${f.awakeMin / 60} hours" to "If that is right: " + LAB.replaceFirstChar(Char::lowercase)
    f.awakeMin >= AWAKE_MIN -> "Awake for about ${f.awakeMin / 60} hours" to "Since you woke at ${f.wokeAt}. $LAB"
    f.sleepMin < SLEEP_VERY_SHORT_MIN -> "Short on sleep for driving" to "The ring measured ${f.asleep} of sleep in the last 24 hours. " +
        "In US crash data, drivers who slept under 4 hours had about 11 times the crash risk of those who slept 7 or more."
    f.sleepMin < SLEEP_SHORT_MIN -> "Short on sleep for driving" to "The ring measured ${f.asleep} of sleep in the last 24 hours. " +
        "In US crash data, drivers who slept 4 to 5 hours had about 4 times the crash risk of those who slept 7 or more."
    else -> null
}

/** The picked car connected: one Sync log line, and the warning once per wake period. Never an all-clear. */
class DriveCheckReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val car = ctx.carAddress
        if (car.isEmpty() || IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address != car) return
        val facts = driveFacts(ctx, System.currentTimeMillis())
        val warning = facts?.let(::driveWarning)
        if (facts == null || warning == null) return SyncLog.add(ctx, "Drive check: car connected, " +
            (facts?.let { "${it.asleep} asleep, awake ${it.awake}, no warning" } ?: "no fresh ring data"))
        val post = facts.wake != ctx.driveWarnedWake &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        SyncLog.add(ctx, "Drive check: car connected, ${facts.asleep} asleep, awake ${facts.awake}, ${warning.first}" + if (post) "" else " (not shown)")
        if (!post) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("health", "Health", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(5, NotificationCompat.Builder(ctx, "health")
            .setSmallIcon(R.drawable.ic_stat_circa).setContentTitle(warning.first).setContentText(warning.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(warning.second)) // two sentences: the collapsed line cuts the second
            .setContentIntent(open).setAutoCancel(true).build())
        ctx.driveWarnedWake = facts.wake
    }
}
