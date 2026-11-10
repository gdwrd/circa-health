package dev.ring.health

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dev.ring.health.Prefs.openMove
import dev.ring.health.Prefs.phoneMoves
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

/** Asks for ENTER and EXIT of walking, running and cycling; registering again replaces the old request (lost on reboot and app update). */
fun registerMoves(ctx: Context) {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
    val req = ActivityTransitionRequest(KINDS.keys.flatMap { type ->
        listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
            .map { ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build() }
    })
    runCatching { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(req, movePending(ctx)) }
        .onSuccess { t ->
            t.addOnSuccessListener { android.util.Log.i("Moves", "registered") }
            t.addOnFailureListener { SyncLog.add(ctx, "Moves: registration failed (${it.message})") }
        }
        .onFailure { SyncLog.add(ctx, "Moves: registration failed (${it.message})") }
}

fun unregisterMoves(ctx: Context) {
    ctx.openMove = ""
    runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(movePending(ctx)) }
}

/** ENTER keeps start and kind; EXIT writes the sample (start, minutes). An ENTER over an open move, or an EXIT without its start, drops the old one. */
class MoveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ctx.phoneMoves || !ActivityTransitionResult.hasResult(intent)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (e in events) {
                    val metric = KINDS[e.activityType] ?: continue
                    val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000
                    if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) { ctx.openMove = "$metric|$at"; continue }
                    val (kind, start) = ctx.openMove.split('|').let { it[0] to it.getOrNull(1)?.toLongOrNull() }
                    ctx.openMove = ""
                    val min = if (start == null || kind != metric) 0L else (at - start + 30_000) / 60_000
                    if (min < 1) continue
                    pushSample(ctx, metric, min.toDouble(), start!!)
                    SyncLog.add(ctx, "Moves: ${metric.removePrefix("phone_")} $min min from ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))}")
                }
            } finally { pending.finish() }
        }
    }
}
