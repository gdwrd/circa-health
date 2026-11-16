package com.nsheremet.circa

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.nsheremet.circa.Prefs.movesRegistered
import com.nsheremet.circa.Prefs.openMove
import com.nsheremet.circa.Prefs.phoneMoves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val KINDS = mapOf(DetectedActivity.WALKING to "phone_walk", DetectedActivity.RUNNING to "phone_run", DetectedActivity.ON_BICYCLE to "phone_ride")

// Mutable: Play services adds the transition result to the intent. Explicit target, so API 34 accepts it.
private fun movePending(ctx: Context) =
    PendingIntent.getBroadcast(ctx, 0, Intent(ctx, MoveReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** "bootCount|versionCode": the registration dies on reboot and app update, so a change of either means register again. */
fun movesKey(ctx: Context): String =
    "${Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)}|${ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode}"

/** Asks for ENTER and EXIT of walking, running and cycling; registering again replaces the old request (lost on reboot and app update). */
fun registerMoves(ctx: Context) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
    val req = ActivityTransitionRequest(KINDS.keys.flatMap { type ->
        listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            .map { ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build() }
    })
    runCatching { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(req, movePending(ctx)) }
        .onSuccess { t ->
            t.addOnSuccessListener { android.util.Log.i("Moves", "registered"); ctx.movesRegistered = movesKey(ctx) }
            t.addOnFailureListener { SyncLog.add(ctx, "Moves: registration failed (${it.message})") }
        }
        .onFailure { SyncLog.add(ctx, "Moves: registration failed (${it.message})") }
}

fun unregisterMoves(ctx: Context) {
    ctx.openMove = ""
    ctx.movesRegistered = ""
    runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(movePending(ctx)) }
}

/** ENTER keeps start and kind; EXIT writes the sample (start, minutes). An ENTER over an open move, or an EXIT without its start, drops the old one. */
class MoveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents
        if (events.isNullOrEmpty()) { SyncLog.add(ctx, "Moves: empty broadcast"); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
                for (e in events) {
                    val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000
                    val enter = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    SyncLog.add(ctx, "Moves: ${KINDS[e.activityType]?.removePrefix("phone_") ?: "type ${e.activityType}"} ${if (enter) "started" else "ended"} ${hhmm.format(Date(at))}")
                    if (!ctx.phoneMoves) continue
                    val metric = KINDS[e.activityType] ?: continue
                    if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) { ctx.openMove = "$metric|$at"; continue }
                    val (kind, start) = ctx.openMove.split('|').let { it[0] to it.getOrNull(1)?.toLongOrNull() }
                    ctx.openMove = ""
                    val min = if (start == null || kind != metric) 0L else (at - start + 30_000) / 60_000
                    if (min < 1) continue
                    pushSample(ctx, metric, min.toDouble(), start!!)
                    SyncLog.add(ctx, "Moves: ${metric.removePrefix("phone_")} $min min from ${hhmm.format(Date(start))}")
                }
            } finally { pending.finish() }
        }
    }
}
