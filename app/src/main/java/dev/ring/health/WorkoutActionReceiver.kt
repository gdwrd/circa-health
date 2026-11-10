package dev.ring.health

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.ring.health.ui.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Buttons on the "Was this a workout?" notification: store the label on the server, then clear the notification. */
class WorkoutActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val date = runCatching { LocalDate.parse(i.getStringExtra("date")) }.getOrNull() ?: return
        val start = i.getStringExtra("start")?.toLongOrNull() ?: return
        val kind = i.getStringExtra("kind") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A failed call keeps the notification, so the button can be pressed again.
                if (Api.setWorkoutKind(ctx, date, start, kind) != null)
                    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(i.getIntExtra("notif", 0))
            } finally {
                pending.finish()
            }
        }
    }
}
