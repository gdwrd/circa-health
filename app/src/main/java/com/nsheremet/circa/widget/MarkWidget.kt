package com.nsheremet.circa.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.nsheremet.circa.MainActivity
import com.nsheremet.circa.Prefs.markNames
import com.nsheremet.circa.mark
import com.nsheremet.circa.ui.AppTheme
import java.util.Date
import java.util.TimeZone
import androidx.glance.unit.ColorProvider as Solid

private val MARKED = longPreferencesKey("marked") // Glance state of this widget, not Prefs
private val NAMES = stringPreferencesKey("names") // only the redraw trigger; the content reads Prefs
private const val HOUR_MS = 60 * 60 * 1000L

/**
 * Home-screen widget: one tap marks the first named thing (Settings › Things to mark). Undo is in the app.
 * shortcut: slot 1 only (the first named thing), add a configuration screen if a second thing needs a widget
 */
class MarkWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Colours as RingWidget: Theme.kt's light palette, night reuses its card and brandTile.
            val c = AppTheme.colors
            val small = TextStyle(color = Solid(c.secondaryLabel), fontSize = 12.sp, textAlign = TextAlign.Center)
            val name = context.markNames.firstOrNull { it.isNotEmpty() }
            val ms = currentState(MARKED) ?: 0L
            val now = System.currentTimeMillis()
            val off = TimeZone.getDefault().getOffset(now)
            Column(
                GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
                    .background(ColorProvider(day = c.card, night = c.brandTile))
                    .padding(8.dp).clickable(if (name == null) actionStartActivity<MainActivity>() else actionRunCallback<MarkAction>()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (name == null) Text("Name a thing to mark", style = small)
                else {
                    // The clock line's dot.
                    Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(c.tint)) {}
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        name, maxLines = 2,
                        style = TextStyle(color = ColorProvider(day = c.label, night = c.card), fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    )
                    Text(
                        if ((ms + off) / HOUR_MS == (now + off) / HOUR_MS) "Marked " + DateFormat.getTimeFormat(context).format(Date(ms)) else "Tap to mark",
                        style = small,
                    )
                }
            }
        }
    }

    /** After the names changed. A live Glance session only recomposes when its state changes, so the names go in it too. */
    suspend fun updateAll(context: Context) {
        GlanceAppWidgetManager(context).getGlanceIds(MarkWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { it[NAMES] = context.markNames.joinToString("\n") }
            update(context, id)
        }
    }
}

class MarkAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val slot = context.markNames.indexOfFirst { it.isNotEmpty() } + 1
        if (slot == 0) return
        val ts = System.currentTimeMillis()
        mark(context, "mark_$slot", ts)
        updateAppWidgetState(context, glanceId) { it[MARKED] = ts }
        MarkWidget().update(context, glanceId)
    }
}

class MarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MarkWidget()
}
