package dev.ring.health.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.ring.health.MainActivity
import dev.ring.health.Prefs.todayJson
import dev.ring.health.Prefs.todayJsonMs
import dev.ring.health.ui.AppTheme
import dev.ring.health.ui.calmWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Date
import kotlin.math.roundToInt
import androidx.glance.unit.ColorProvider as Solid

/** Home-screen widget: the last /today snapshot SyncWorker stored; never touches the network or BLE. */
class RingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = runCatching { Json.parseToJsonElement(context.todayJson).jsonObject }.getOrNull()
        val ms = context.todayJsonMs
        provideContent { Content(d, ms) }
    }
}

private const val STALE_MS = 2 * 60 * 60 * 1000L

private fun JsonObject.score(k: String): Int? =
    (get(k) as? JsonObject)?.get("score")?.jsonPrimitive?.doubleOrNull?.roundToInt()

@Composable
private fun Content(d: JsonObject?, ms: Long) {
    val ctx = LocalContext.current
    // Outside AppTheme the local holds Theme.kt's light palette. Ring colours are the same in dark;
    // night text and background reuse its card (white) and brandTile (the launcher icon's near-black).
    val c = AppTheme.colors
    val text = ColorProvider(day = c.label, night = c.card)
    val small = TextStyle(color = Solid(c.secondaryLabel), fontSize = 12.sp)
    val size = LocalSize.current
    val footer = if (d != null && System.currentTimeMillis() - ms <= STALE_MS)
        "Updated " + DateFormat.getTimeFormat(ctx).format(Date(ms)) else "Open to sync"
    // Calm mode (/today.calm): the open mark and a word per score, no numbers.
    val calm = d?.get("calm")?.jsonPrimitive?.content == "true"
    val medium = TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val room = size.height - 24.dp - 40.dp // padding + two text lines
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
            .background(ColorProvider(day = c.card, night = c.brandTile))
            .padding(12.dp).clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (size.width >= 200.dp) {
            val ring = minOf((size.width - 24.dp) / 3 - 12.dp, room - if (calm) 16.dp else 0.dp).coerceIn(40.dp, 96.dp) // calm: one more text line
            Row(GlanceModifier.fillMaxWidth()) {
                listOf(Triple("Sleep", "sleep", c.sleep), Triple("Readiness", "readiness", c.readiness), Triple("Activity", "activity", c.activity))
                    .forEach { (label, key, col) ->
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Ring(d?.score(key), col, c.light(col), ring, text, calm)
                            if (calm) Text(calmWord(key, d?.score(key)), style = medium)
                            Text(label, style = small)
                        }
                    }
            }
            Spacer(GlanceModifier.height(4.dp))
            val level = listOf("calm", "elevated", "high").indexOf((d?.get("signals") as? JsonObject)?.get("level")?.jsonPrimitive?.content)
            Row {
                if (level >= 0) Text(
                    "Body signals " + listOf("Calm", "Elevated", "High")[level] + " · ", style = medium,
                )
                Text(footer, style = small)
            }
        } else {
            val ring = minOf(size.width - 24.dp, room).coerceIn(40.dp, 110.dp)
            Ring(d?.score("readiness"), c.readiness, c.light(c.readiness), ring, text, calm)
            Spacer(GlanceModifier.height(4.dp))
            Text(
                if (calm) calmWord("readiness", d?.score("readiness")) else "Sleep ${d?.score("sleep") ?: "–"} · Activity ${d?.score("activity") ?: "–"}",
                style = medium,
            )
            Text(footer, style = small)
        }
    }
}

/** ScoreRing's look as a bitmap (Glance has no Canvas): 12 % track, round-cap sweep gradient to the light partner. */
@Composable
private fun Ring(score: Int?, color: Color, light: Color, size: Dp, text: androidx.glance.unit.ColorProvider, calm: Boolean) {
    val px = (size.value * LocalContext.current.resources.displayMetrics.density).roundToInt()
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(if (calm) markBitmap(px, px * 0.12f, color, light) else ringBitmap(px, px * 0.12f, (score ?: 0) / 100f, color, light)), null, GlanceModifier.fillMaxSize())
        if (!calm) Text(score?.toString() ?: "–", style = TextStyle(color = text, fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold))
    }
}

/** Calm mode: the open Circa mark as Today's CalmArc draws it (thin tail at 334.6° round to the thick head at 296°, dot in the gap). */
private fun markBitmap(px: Int, head: Float, color: Color, light: Color): Bitmap {
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cv = Canvas(b)
    val r = RectF(head / 2, head / 2, px - head / 2, px - head / 2)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    repeat(90) { i ->
        val t = i / 89f
        p.color = lerp(color, light, t).toArgb()
        p.strokeWidth = head * (4 + 5 * t) / 9
        cv.drawArc(r, 334.6f + 321.4f * i / 90, 321.4f / 90, false, p)
    }
    p.style = Paint.Style.FILL
    p.color = light.toArgb()
    cv.drawCircle(px / 2f + r.width() / 2 * 0.724f, px / 2f - r.width() / 2 * 0.69f, head / 3, p)
    return b
}

private fun ringBitmap(px: Int, stroke: Float, progress: Float, color: Color, light: Color): Bitmap {
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cv = Canvas(b)
    val r = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color.copy(alpha = 0.12f).toArgb() }
    cv.drawOval(r, p)
    val v = progress.coerceIn(0f, 1f)
    if (v > 0f) {
        val mid = px / 2f
        cv.rotate(-90f, mid, mid)
        // As ScoreRing: back to the base colour after the head so the start cap does not pick up the light end.
        val (cols, pos) = if (v < 0.999f) intArrayOf(color.toArgb(), light.toArgb(), color.toArgb()) to floatArrayOf(0f, v, 1f)
        else intArrayOf(color.toArgb(), light.toArgb()) to floatArrayOf(0f, 1f)
        p.alpha = 255 // the paint's alpha multiplies the shader
        p.shader = SweepGradient(mid, mid, cols, pos)
        p.strokeCap = Paint.Cap.ROUND
        cv.drawArc(r, 0f, 360f * v, false, p)
    }
    return b
}
