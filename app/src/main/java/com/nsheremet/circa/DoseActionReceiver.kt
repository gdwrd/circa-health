package com.nsheremet.circa

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nsheremet.circa.ui.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** "Taken" on the "Dose logged?" notification: log the dose at the minute of the press, then clear the notification. */
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("notif", 0)
        val at = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Api.addJournal(ctx, LocalDate.now().toString(), "medication", at) != null) nm.cancel(id)
                // A failed write keeps the notification (and its button) and says so.
                else nm.activeNotifications.firstOrNull { it.id == id }?.let {
                    nm.notify(id, Notification.Builder.recoverBuilder(ctx, it.notification).setContentText("Not saved").setOnlyAlertOnce(true).build())
                }
            } finally {
                pending.finish()
            }
        }
    }
}
