package dev.ring.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

/** [div] converts the wire value to the displayed unit (minutes -> hours). */
private class Metric(
    val key: String, val label: String, val chip: String, val unit: String,
    val color: (AppColors) -> Color, val columns: Boolean = false, val div: Double = 1.0,
)

private val METRICS = listOf(
    Metric("sleep", "Sleep score", "Sleep", "", { it.sleep }),
    Metric("readiness", "Readiness score", "Readiness", "", { it.readiness }),
    Metric("activity", "Activity score", "Activity", "", { it.activity }),
    Metric("rhr", "Resting heart rate", "Resting HR", "bpm", { it.heart }),
    Metric("hrv", "Heart rate variability", "HRV", "ms", { it.hrv }),
    Metric("steps", "Steps", "Steps", "steps", { it.activity }, true),
    Metric("sleep_min", "Time asleep", "Time asleep", "h", { it.sleep }, true, 60.0),
)

/** x = days since [first]; null values are already dropped. */
private class Pts(val first: LocalDate, val last: LocalDate, val v: List<Pair<Double, Double>>, val b: List<Pair<Double, Double>>)

private fun parse(json: JsonElement?, div: Double): Pts? {
    val pts = (json.obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj() } ?: return null
    val dates = pts.map { LocalDate.parse((it["date"] as JsonPrimitive).content) }
    val first = dates.firstOrNull() ?: return null
    fun col(k: String) = pts.mapIndexedNotNull { i, o -> o[k].num()?.let { (dates[i].toEpochDay() - first.toEpochDay()).toDouble() to it / div } }
    return Pts(first, dates.last(), col("value"), col("baseline"))
}

private fun Metric.format(v: Double) = when (key) {
    "steps" -> "%,d".format(v.roundToInt())
    "sleep_min" -> "%.1f".format(v)
    else -> v.roundToInt().toString()
}

private val MARKER_AXIS = 49.dp // approx. width of the chart's y-axis labels, so markers align with the plot

@Composable
fun TrendsScreen(backend: Boolean?) {
    if (backend != true) return NoBackendTab("Trends", backend)
    val ctx = LocalContext.current
    var m by remember { mutableStateOf(METRICS[0]) }
    var days by remember { mutableIntStateOf(30) }
    var data by remember { mutableStateOf<Pts?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var journal by remember { mutableStateOf<List<Pair<LocalDate, String>>>(emptyList()) }
    var week by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() {
        week = runCatching { Api.week(ctx).obj() }.getOrNull()
        journal = runCatching {
            (Api.journal(ctx, days).obj()?.get("entries") as JsonArray).mapNotNull { it.obj() }.map {
                LocalDate.parse((it["date"] as JsonPrimitive).content) to (it["kind"] as JsonPrimitive).content
            }
        }.getOrDefault(emptyList())
        data = runCatching { parse(Api.trends(ctx, m.key, days), m.div) }.getOrNull()
    }
    LaunchedEffect(m, days) {
        loaded = false
        load()
        loaded = true
    }

    val c = AppTheme.colors
    LargeTitleScaffold("Trends", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        week?.let { item { WeekCard(it) } }
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                Segmented(listOf("30 D", "90 D"), if (days == 30) 0 else 1) { days = if (it == 0) 30 else 90 }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    METRICS.forEach { x ->
                        val sel = x == m
                        Text(
                            x.chip, style = AppType.subhead.copy(fontWeight = FontWeight.Medium),
                            color = if (sel) Color.White else c.label,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) Brush.horizontalGradient(listOf(x.color(c), c.light(x.color(c)))) else SolidColor(c.card))
                                .clickable { m = x }.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
        item {
            InsetCard {
                val d = data
                if (!loaded) return@InsetCard Box(Modifier.height(300.dp))
                if (d == null || d.v.isEmpty()) return@InsetCard Text(
                    "Not enough data yet", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l),
                )
                val avg = d.v.map { it.second }.average()
                val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                Text(m.label, style = AppType.headline, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l))
                Text("Average", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l))
                Text(
                    buildAnnotatedString {
                        append(m.format(avg))
                        if (m.unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 17.sp, color = c.secondaryLabel)) { append(" ${m.unit}") }
                    },
                    style = AppType.largeTitle, modifier = Modifier.padding(start = AppSpace.l),
                )
                Text(
                    "${d.first.format(dateFmt)} – ${d.last.format(dateFmt)}", style = AppType.subhead, color = c.secondaryLabel,
                    modifier = Modifier.padding(start = AppSpace.l),
                )
                androidx.compose.runtime.key(m, days) { TrendChart(d, m, m.color(c), avg) }
                val inPeriod = journal.filter { it.first >= d.first && it.first <= d.last }
                if (inPeriod.isNotEmpty()) {
                    val span = maxOf(d.last.toEpochDay() - d.first.toEpochDay(), 1L)
                    val dot = c.secondaryLabel
                    Canvas(Modifier.fillMaxWidth().height(10.dp).padding(start = AppSpace.l + MARKER_AXIS, end = AppSpace.l)) {
                        inPeriod.forEach {
                            val f = ((it.first.toEpochDay() - d.first.toEpochDay()) + 0.5) / (span + 1.5)
                            drawCircle(dot, 2.5.dp.toPx(), Offset((f * size.width).toFloat(), size.height / 2))
                        }
                    }
                    Text(
                        "In your journal: " + inPeriod.groupingBy { it.second }.eachCount().entries.joinToString(", ") { "${KIND_LABEL[it.key] ?: it.key} ×${it.value}" },
                        style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l),
                    )
                }
            }
        }
    }
}

private fun day(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.let(LocalDate::parse)

/** Seven readiness rings, score means with ▲/▼ vs the previous week, best/worst night, steps + sleep; learning under 3 scored days. */
@Composable
private fun WeekCard(w: JsonObject) {
    val c = AppTheme.colors
    val days = (w["days"] as? JsonArray)?.mapNotNull { it.obj() } ?: return
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val dow = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    val scored = w["scored_days"].num()?.toInt() ?: 0
    InsetCard {
        Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
            Column {
                Text("This week", style = AppType.headline)
                Text("${day(w["start"])?.format(fmt)} – ${day(w["end"])?.format(fmt)}", style = AppType.footnote, color = c.secondaryLabel)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { d ->
                    val r = d["readiness"].num()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ScoreRing(((r ?: 0.0) / 100).toFloat(), c.readiness, Modifier.size(28.dp), stroke = 3.dp) {
                            Text(
                                r?.roundToInt()?.toString() ?: "–", style = AppType.tab.copy(fontWeight = FontWeight.SemiBold),
                                color = if (r == null) c.secondaryLabel else c.label,
                            )
                        }
                        Text(
                            day(d["date"])?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()).orEmpty(),
                            style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
                        )
                    }
                }
            }
            if (scored < 3) return@Column Text(
                "Learning your week · $scored/3 days scored. Your summary appears after 3.", style = AppType.subhead, color = c.secondaryLabel,
            )
            val means = w["means"].obj()
            val prev = w["previous"].obj()?.get("means").obj()
            Row(Modifier.fillMaxWidth()) {
                listOf("sleep" to "Sleep", "readiness" to "Readiness", "activity" to "Activity").forEach { (k, label) ->
                    val v = means?.get(k).num()?.roundToInt()
                    val p = prev?.get(k).num()?.roundToInt()
                    val delta = if (v != null && p != null) v - p else 0
                    Column(Modifier.weight(1f)) {
                        Text(label, style = AppType.footnote, color = c.secondaryLabel)
                        Text(
                            buildAnnotatedString {
                                append(v?.toString() ?: "–")
                                // Every score here is better when higher, so up is good.
                                if (delta != 0) withStyle(SpanStyle(fontSize = 13.sp, color = if (delta > 0) c.goodText else c.badText)) {
                                    append(if (delta > 0) " ▲$delta" else " ▼${-delta}")
                                }
                            },
                            style = AppType.headline,
                        )
                    }
                }
            }
            val best = day(w["best_night"].obj()?.get("date"))
            val worst = day(w["worst_night"].obj()?.get("date"))?.takeIf { it != best }
            if (best != null) Text(listOfNotNull("Best night ${best.format(dow)}", worst?.let { "Worst ${it.format(dow)}" }).joinToString(" · "), style = AppType.subhead)
            val steps = w["steps_total"].num()
            val line = listOfNotNull(
                steps?.let { if (it >= 10_000) "Steps ${(it / 1000).roundToInt()} k" else "Steps %,d".format(it.roundToInt()) },
                w["sleep_avg_min"].num()?.let { "Sleep ${duration(it.roundToInt())} avg" },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = AppType.subhead)
        }
    }
}

/** iOS segmented control: rounded gray track, raised thumb under the selected option. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = AppTheme.colors
    val thumb = if (c.background.luminance() < 0.5f) c.secondaryLabel.copy(alpha = 0.5f) else c.card
    Row(Modifier.fillMaxWidth().clip(AppShape.small).background(c.secondaryLabel.copy(alpha = 0.16f)).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (sel) Modifier.background(thumb) else Modifier).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = AppType.footnote.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium))
            }
        }
    }
}

/** Line (scores, rhr, hrv) or columns (steps, time asleep) in [color]; baseline as the wide translucent band from HeartScreen. */
@Composable
private fun TrendChart(p: Pts, m: Metric, color: Color, avg: Double) {
    val producer = remember { CartesianChartModelProducer() }
    val hasBase = p.b.isNotEmpty()
    LaunchedEffect(p) {
        producer.runTransaction {
            if (m.columns) columnSeries { series(p.v.map { it.first }, p.v.map { it.second }) }
            if (!m.columns || hasBase) lineSeries {
                if (!m.columns) series(p.v.map { it.first }, p.v.map { it.second })
                if (hasBase) series(p.b.map { it.first }, p.b.map { it.second })
            }
        }
    }
    val ys = (p.v + p.b).map { it.second }
    val top = ys.max()
    val (lo, hi, step) = when {
        m.columns -> {
            val raw = maxOf(top / 3, 1.0)
            val mag = 10.0.pow(floor(log10(raw)))
            val s = listOf(1.0, 2.0, 3.0, 5.0, 10.0).first { it * mag >= raw } * mag
            Triple(0.0, ceil(top * 1.05 / s) * s, s)
        }
        m.unit.isEmpty() -> Triple(0.0, 100.0, 25.0)
        else -> {
            val span = top - ys.min()
            val s = if (span > 60) 20.0 else if (span > 24) 10.0 else 5.0
            val l = floor((ys.min() - span * 0.1) / s) * s
            Triple(l, maxOf(ceil((top + span * 0.1) / s) * s, l + s), s)
        }
    }
    val span = maxOf(p.last.toEpochDay() - p.first.toEpochDay(), 1L)
    // Fixed x span keeps a lone column column-sized instead of chart-wide.
    val range = CartesianLayerRangeProvider.fixed(minX = -0.5, maxX = span + 1.0, minY = lo, maxY = hi)
    val band = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color.copy(alpha = 0.18f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 22f),
    )
    val colLayer = if (m.columns) {
        rememberColumnCartesianLayer(
            ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(
                    fill = fill(color), thickness = if (span > 30) 5.dp else 9.dp,
                    shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30),
                ),
            ),
            columnCollectionSpacing = 1.dp,
            rangeProvider = range,
        )
    } else null
    val lineLayer = if (!m.columns || hasBase) {
        val main = chartLine(color, area = true)
        rememberLineCartesianLayer(
            if (m.columns || !hasBase) LineCartesianLayer.LineProvider.series(if (m.columns) band else main)
            else LineCartesianLayer.LineProvider.series(main, band),
            rangeProvider = range,
        )
    } else null
    val kFmt = DecimalFormat("0.#")
    val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val byX = p.v.toMap()
    val unit = if (m.unit.isEmpty()) "" else " ${m.unit}"
    ScrubChartHost(
        rememberCartesianChart(
            *listOfNotNull<CartesianLayer<*>>(colLayer, lineLayer).toTypedArray(),
            startAxis = startAxis(step, CartesianValueFormatter { _, y, _ -> if (y >= 1000) kFmt.format(y / 1000) + "k" else kFmt.format(y) }),
            bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> p.first.plusDays(x.toLong()).format(dateFmt) }, if (span > 30) 15 else 7),
            marker = rememberScrubMarker(color) { x -> "${byX[x]?.let { m.format(it) + unit } ?: "—"} · ${p.first.plusDays(x.toLong()).format(dateFmt)}" },
            decorations = listOf(dashedLine(avg, AppTheme.colors.secondaryLabel)),
        ),
        producer, 220.dp,
    )
}
