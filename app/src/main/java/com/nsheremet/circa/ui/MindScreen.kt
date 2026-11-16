package com.nsheremet.circa.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nsheremet.circa.SampleStore
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
private fun day(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.let(LocalDate::parse)

/**
 * Body › Mind: the mood chart (30 days), questionnaire results with "Answer now", medication by dose and the
 * titration PDF. A part with nothing to show does not draw; with none of them, one empty state.
 */
@Composable
fun MindScreen(backend: Boolean?, header: @Composable () -> Unit) {
    if (backend != true) return NoBackendTab("Body", backend)
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var loaded by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<JsonObject?>(null) }
    var mood by remember { mutableStateOf<List<JsonObject>>(emptyList()) } // the last 30 days, oldest first
    var phq by remember { mutableStateOf(emptyList<Pair<Double, Double>>()) } // questionnaire results (ts s, total), oldest first, 90 days
    var med by remember { mutableStateOf<JsonObject?>(null) }
    var rhr90 by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var phqOpen by remember { mutableStateOf(false) }
    var removePhq by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    fun phqList(j: JsonObject?) = (j?.get("phq") as? JsonArray)?.mapNotNull { o ->
        val p = o.obj() ?: return@mapNotNull null
        (p["ts"].num() ?: return@mapNotNull null) to (p["total"].num() ?: return@mapNotNull null)
    }
    fun phqLabel(r: Pair<Double, Double>): Pair<String, String> {
        val d = java.time.Instant.ofEpochMilli((r.first * 1000).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d MMM" else "d MMM yyyy", Locale.getDefault())) to "${r.second.toInt()} of 27"
    }
    suspend fun load() {
        Api.settings(ctx)?.let { settings = it }
        Api.mind(ctx, 90)?.let { m ->
            mood = (m["days"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty().takeLast(30)
            phqList(m)?.let { phq = it }
        }
        if (settings?.get("medication_started").str().isNullOrBlank().not()) {
            Api.insights(ctx).obj()?.let { med = it["kinds"].obj()?.get("medication").obj() }
            (Api.trends(ctx, "rhr", 90).obj()?.get("points") as? JsonArray)?.let { a -> rhr90 = a.mapNotNull { it.obj() } }
        }
        loaded = true
    }
    // Read again when the questionnaire closes, as Settings › Mind did.
    LaunchedEffect(phqOpen) { if (!phqOpen) load() }

    val started = settings?.get("medication_started").str()?.takeIf { it.isNotBlank() }
    val firstStep = settings?.let { Api.doseSteps(it).firstOrNull() }
    val moodFrom = day(mood.firstOrNull()?.get("date"))?.atStartOfDay(java.time.ZoneId.systemDefault())?.toEpochSecond()
    val moodPhq = phq.filter { moodFrom != null && it.first >= moodFrom }.map { it.first.toLong() to it.second.toInt() }
    val showMood = moodPhq.isNotEmpty() || mood.any { it["energy"].num() != null || it["valence"].num() != null }
    val showPhq = settings?.get("phq").str() == "1" || phq.isNotEmpty()
    LargeTitleScaffold("Body", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        item { Box(Modifier.padding(bottom = AppSpace.m)) { header() } }
        if (!loaded) return@LargeTitleScaffold
        if (!showMood && !showPhq && started == null) item {
            InsetCard { EmptyState(Icons.Rounded.Psychology, "Nothing here yet", "Your mood check-ins, questionnaire results and medication by dose will show here.") }
        }
        if (showMood) item { MoodCard(mood, moodPhq) }
        if (showPhq) item {
            Section("Questionnaire") {
                phq.reversed().forEachIndexed { i, r ->
                    if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                    val (d, total) = phqLabel(r)
                    Box(Modifier.clickable(onClickLabel = "Remove") { haptic.tick(); removePhq = r }) { ValueRow(d, total) }
                }
                Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                    if (phq.isNotEmpty()) Text("Tap a result to remove it. To correct one, remove it and answer again.", style = AppType.footnote, color = c.secondaryLabel)
                    TintButton("Answer now") { haptic.tick(); phqOpen = true }
                }
            }
        }
        started?.let { st -> item { MedicationCard(med, st, rhr90) } }
        if (started != null && firstStep != null) item {
            TintButton("Titration summary (PDF)") {
                if (!sharing) { haptic.tick(); shareFile(activity, "circa-titration.pdf", "application/pdf", { sharing = it }) { loadTitration(ctx, settings!!, firstStep) } }
            }
        }
    }
    if (phqOpen) PhqSheet { phqOpen = false }
    removePhq?.let { r ->
        val (d, total) = phqLabel(r)
        AlertDialog(
            onDismissRequest = { removePhq = null },
            containerColor = c.card,
            title = { Text("Remove this result?") },
            text = { Text("$d · $total", style = AppType.body) },
            confirmButton = {
                TextButton({
                    removePhq = null
                    activity.lifecycleScope.launch {
                        val left = phqList(Api.deletePhq(ctx, r.first))
                        if (left == null) Toast.makeText(ctx, "Could not remove the result", Toast.LENGTH_SHORT).show()
                        else {
                            phq = left
                            // The phone's copy too, or "Send all history" would push it back.
                            withContext(Dispatchers.IO) {
                                SampleStore.get(ctx).writableDatabase.delete("samples", "name IN ('phq9_total', 'phq9_item9') AND ts = ?",
                                    arrayOf(Math.round(r.first * 1000).toString()))
                            }
                        }
                    }
                }) { Text("Remove", color = c.badText) }
            },
            dismissButton = { TextButton({ removePhq = null }) { Text("Cancel", color = c.tint) } },
        )
    }
}

/**
 * Energy and mood per day (1..5) as two lines over [days] (oldest first); days without a check-in are gaps; dose-step changes are vertical lines.
 * Questionnaire totals ([phq]: epoch s, total) sit as labelled points in a lane under the lines (y 0..1, no axis label).
 */
@Composable
private fun MoodCard(days: List<JsonObject>, phq: List<Pair<Long, Int>>) {
    val c = AppTheme.colors
    val first = day(days.firstOrNull()?.get("date")) ?: return
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val zone = java.time.ZoneId.systemDefault()
    val points = phq.map { (ts, total) -> (java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toEpochDay() - first.toEpochDay()).toDouble() to total }
        .filter { it.first >= 0 && it.first < days.size }
        .sortedBy { it.first }.associate { it }.toList() // one point a day: its latest total (the backend lists them by time)
    val minY = if (points.isEmpty()) 1.0 else 0.0
    // Runs of consecutive days with a value: each run is its own series, so a missing day breaks the line.
    fun runs(k: String) = days.indices.mapNotNull { i -> days[i][k].num()?.let { i.toDouble() to it } }
        .fold(mutableListOf<MutableList<Pair<Double, Double>>>()) { acc, p -> acc.apply { if (lastOrNull()?.last()?.first == p.first - 1) last() += p else add(mutableListOf(p)) } }
    val energy = runs("energy")
    val mood = runs("valence")
    val steps = days.indices.filter { i -> i > 0 && days[i]["mg"].num().let { it != null && it != days[i - 1]["mg"].num() } }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days) { producer.runTransaction { lineSeries { (energy + mood).forEach { r -> series(r.map { it.first }, r.map { it.second }) } } } }
    @Composable
    fun scaleLine(color: Color) = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 2.5f),
        // A lone day has no segment to draw: the dot keeps it visible.
        pointProvider = LineCartesianLayer.PointProvider.single(LineCartesianLayer.Point(rememberShapeComponent(fill(color), CorneredShape.Pill), 6f)),
    )
    val eLine = scaleLine(c.energy)
    val mLine = scaleLine(c.mood)
    InsetCard {
        Text("Mood", style = AppType.headline, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l))
        Text("${first.format(fmt)} – ${first.plusDays(days.size - 1L).format(fmt)} · 1 to 5", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l))
        Row(Modifier.padding(start = AppSpace.l, top = AppSpace.s), horizontalArrangement = Arrangement.spacedBy(AppSpace.m), verticalAlignment = Alignment.CenterVertically) {
            listOf("Energy" to c.energy, "Mood" to c.mood).forEach { (name, col) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                    Text(name, style = AppType.footnote, color = c.secondaryLabel)
                }
            }
            if (steps.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                Box(Modifier.size(width = 2.dp, height = 12.dp).background(c.pill))
                Text("Dose step", style = AppType.footnote, color = c.secondaryLabel)
            }
            if (points.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.mind))
                Text("PHQ-9", style = AppType.footnote, color = c.secondaryLabel)
            }
        }
        ScrubChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(energy.map { eLine } + mood.map { mLine }),
                    rangeProvider = CartesianLayerRangeProvider.fixed(minX = -0.5, maxX = days.size.toDouble(), minY = minY, maxY = 5.0),
                ),
                startAxis = startAxis(1.0, CartesianValueFormatter { _, y, _ -> if (y < 1) " " else y.roundToInt().toString() }),
                bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> first.plusDays(x.toLong()).format(fmt) }, if (days.size > 31) 15 else 7),
                marker = rememberScrubMarker(c.energy) { x ->
                    val d = days.getOrNull(x.roundToInt())
                    (listOfNotNull(
                        d?.get("energy").num()?.let { "Energy %.1f".format(it) }, d?.get("valence").num()?.let { "Mood %.1f".format(it) },
                    ) + first.plusDays(x.toLong()).format(fmt)).joinToString(" · ")
                },
                decorations = listOf(DoseWindows(steps.map { it.toDouble() to it.toDouble() }, c.pill.toArgb(), alpha = 0), PhqPoints(points, c.mind.toArgb())),
            ),
            producer, 200.dp,
        )
        // No-break spaces: a line breaks only after a "·", never inside one step.
        if (steps.isNotEmpty()) Text(
            steps.joinToString("\u00A0· ") { "${Api.mgLabel(days[it]["mg"].num()!!)} from ${first.plusDays(it.toLong()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))}".replace(' ', '\u00A0') },
            style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.l),
        )
    }
}

/** Each questionnaire total as a dot with its number above, in the lane y 0..1 under the mood lines (x as the chart's). */
private class PhqPoints(private val points: List<Pair<Double, Int>>, private val color: Int) : Decoration {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }

    override fun drawOverLayers(context: CartesianDrawingContext) = with(context) {
        val b = layerBounds
        fun px(x: Double) = b.left + layerDimensions.startPadding + ((x - ranges.minX) / ranges.xStep).toFloat() * layerDimensions.xSpacing - scroll
        val lane = b.height() / 5 // y 0..1 of the fixed 0..5 range
        paint.color = color
        paint.textSize = spToPx(11f)
        paint.isFakeBoldText = true
        points.forEach { (x, total) ->
            val cx = px(x)
            canvas.drawCircle(cx, b.bottom - lane * 0.3f, dpToPx(4f), paint)
            val label = total.toString()
            val half = paint.measureText(label) / 2
            canvas.drawText(label, cx.coerceIn(b.left + half, b.right - half), b.bottom - lane * 0.3f - dpToPx(7f), paint)
        }
    }
}

private val MED_ROWS = listOf("day_hr", "rhr", "hrv", "bed_start", "total_min")

@Composable
private fun MedicationCard(med: JsonObject?, started: String, rhr: List<JsonObject>) {
    val c = AppTheme.colors
    val since = runCatching { LocalDate.parse(started).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }.getOrDefault(started)
    val all = (med?.get("all") as? JsonArray)?.mapNotNull { it.obj() }?.associateBy { it["metric"].str() }.orEmpty()
    val nWith = med?.get("n_with").num()?.roundToInt() ?: 0
    val nWithout = med?.get("n_without").num()?.roundToInt() ?: 0
    Section("Medication") {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            if (med?.get("status").str() == "ok" && all.isNotEmpty()) {
                Text("Days with medication vs without", style = AppType.headline)
                Row {
                    Spacer(Modifier.weight(1f))
                    listOf("With", "Without", "Change").forEach {
                        Text(it, style = AppType.footnote, color = c.secondaryLabel, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                    }
                }
                MED_ROWS.forEach { m ->
                    val e = all[m] ?: return@forEach
                    val w = e["with"].num()
                    val wo = e["without"].num()
                    // From the displayed (whole-number) values, so "65 / 65" never reads "+1".
                    val d = if (w != null && wo != null) (w.roundToInt() - wo.roundToInt()).toDouble() else e["delta"].num() ?: 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(METRIC[m]?.first ?: m, style = AppType.subhead, modifier = Modifier.weight(1f))
                        Text(w?.let { metricValue(m, it) } ?: "—", style = AppType.subhead, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                        Text(wo?.let { metricValue(m, it) } ?: "—", style = AppType.subhead, color = c.secondaryLabel, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                        Text(metricDelta(m, d, null), style = AppType.subhead, color = effectColor(m, d), textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                    }
                }
                Text("$nWith days with a dose, $nWithout without, since $since.", style = AppType.footnote, color = c.secondaryLabel)
                val bt = med?.get("by_time").obj()
                val later = (bt?.get("all") as? JsonArray)?.mapNotNull { it.obj() }?.firstOrNull { it["metric"].str() == "bed_start" }?.get("delta").num()
                if (bt?.get("status").str() == "ok" && later != null && kotlin.math.abs(later) >= 5) Text(
                    "Doses after ${clock(bt?.get("split").str().orEmpty())}: you tended to fall asleep ${kotlin.math.abs(later).roundToInt()} min ${if (later > 0) "later" else "earlier"}.",
                    style = AppType.subhead,
                )
            } else {
                val before = rhr.lastOrNull { (it["date"].str() ?: "") < started && it["baseline"].num() != null }?.get("baseline").num()
                val after = rhr.lastOrNull { it["baseline"].num() != null }?.takeIf { (it["date"].str() ?: "") >= started }?.get("baseline").num()
                Text(
                    if (before != null && after != null) "Since you started ($since): resting HR ${before.roundToInt()} → ${after.roundToInt()} bpm" else "Since you started ($since)",
                    style = AppType.headline,
                )
                Text(
                    "Days with and without a dose are compared once there are 3 of each. So far: $nWith with, $nWithout without.",
                    style = AppType.subhead, color = c.secondaryLabel,
                )
            }
            val levels = Api.doseLevels(med).filter { it.n > 0 } // a step with no dose days has nothing to show
            if (levels.isNotEmpty()) {
                Hairline(Modifier.padding(vertical = AppSpace.xs))
                ByDose(levels)
            }
            Text("Averages from your own data. Other things may play a part. Not medical advice.", style = AppType.footnote, color = c.secondaryLabel)
        }
    }
}

// Rows of the By dose table: label and the value as shown ("—" when null: under 3 dose days).
internal val BY_DOSE:List<Pair<String, (Map<String, Double?>) -> String?>> = listOf(
    "Resting HR" to { m -> m["rhr"]?.let { "${it.roundToInt()} bpm" } },
    "Time asleep" to { m -> m["total_min"]?.roundToInt()?.let { "${it / 60} h ${"%02d".format(it % 60)}" } },
    "Bedtime" to { m -> m["bed_start"]?.let(::bedClock) },
    "Energy" to { m -> m["energy"]?.let { "%.1f".format(it) } },
    "Mood" to { m -> m["valence"]?.let { "%.1f".format(it) } },
    "Focus" to { m -> m["focus"]?.let { "%.1f".format(it) } },
    "Appetite" to { m -> m["appetite"]?.let { "%.1f".format(it) } },
    "Crash" to { m -> m["crash"]?.let { "%.1f".format(it) } },
    "Blood pressure" to { m -> m["sys"]?.let { s -> m["dia"]?.let { d -> "${s.roundToInt()}/${d.roundToInt()}" } } },
)

/**
 * Steps as columns, measures as rows (as the with / without table above). The label column stays put; the step
 * columns share one scroll state, so from three steps on they slide together and open at the latest.
 */
@Composable
private fun ByDose(levels: List<Api.DoseLevel>) {
    val c = AppTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val min = (12 + 84 * LocalDensity.current.fontScale).dp // "11:37 PM" plus a gap, at any text size
    Text("By dose", style = AppType.headline)
    BoxWithConstraints {
        // Narrow screen or large text: a label beside the values would leave room for one step only, so it goes above them.
        val stack = levels.size > 1 && (maxWidth - 112.dp) / min < 2f
        val room = if (stack) maxWidth else maxWidth - 112.dp
        // Whole columns only at rest: when the steps overflow, the visible ones widen to fill the space exactly.
        val fit = maxOf(1, (room / min).toInt())
        val col = Modifier.width(if (levels.size <= fit) min else room / fit)
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            @Composable
            fun Line(label: String, cells: @Composable (Api.DoseLevel) -> Unit) {
                @Composable
                fun Values(m: Modifier) = Row(m.horizontalScroll(scroll), horizontalArrangement = Arrangement.End) {
                    levels.forEach { Box(col, contentAlignment = Alignment.CenterEnd) { cells(it) } }
                }
                if (stack) Column {
                    if (label.isNotEmpty()) Text(label, style = AppType.footnote, color = c.secondaryLabel)
                    Values(Modifier.fillMaxWidth())
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.width(112.dp))
                    Values(Modifier.weight(1f))
                }
            }
            Line("") { l ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Api.mgLabel(l.mg), style = AppType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.pill, maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(c.pill.copy(alpha = 0.16f)).padding(horizontal = AppSpace.s, vertical = 2.dp),
                    )
                    Text(l.from.format(fmt), style = AppType.footnote, color = c.secondaryLabel, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Line("Dose days") { Text(it.n.toString(), style = AppType.subhead, fontWeight = FontWeight.SemiBold) }
            BY_DOSE.filter { (_, value) -> levels.any { value(it.means) != null } }.forEach { (label, value) ->
                Hairline()
                Line(label) { l ->
                    val v = value(l.means)
                    Text(v ?: "—", style = AppType.subhead, color = if (v == null) c.secondaryLabel else c.label, maxLines = 1)
                }
            }
            Text(
                (if (levels.size > fit) "Swipe the table for earlier steps. " else "") +
                    "Each step runs from the date under it. Scales are 1 to 5. “—”: under 3 dose days on that step.",
                style = AppType.footnote, color = c.secondaryLabel,
            )
        }
    }
}
