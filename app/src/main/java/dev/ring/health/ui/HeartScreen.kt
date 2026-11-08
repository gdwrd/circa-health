package dev.ring.health.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import dev.ring.health.Prefs.ringAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private const val SLOT_S = 300.0

private class Trend(val first: LocalDate, val value: List<Pair<Double, Double>>, val base: List<Pair<Double, Double>>)

private fun trend(json: JsonElement?): Trend? {
    val pts = (json.obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj() } ?: return null
    val dates = pts.map { LocalDate.parse((it["date"] as JsonPrimitive).content) }
    val first = dates.firstOrNull() ?: return null
    fun col(k: String) = pts.mapIndexedNotNull { i, o -> o[k].num()?.let { (dates[i].toEpochDay() - first.toEpochDay()).toDouble() to it } }
    return Trend(first, col("value"), col("baseline"))
}

@Composable
fun HeartScreen(backend: Boolean?) {
    if (backend != true) return PlaceholderTab("Heart", backend)
    val ctx = LocalContext.current
    var hr by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var rhr by remember { mutableStateOf<Trend?>(null) }
    var hrv by remember { mutableStateOf<Trend?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hr = (Api.hr(ctx, 24).obj()?.get("points") as? JsonArray)
            ?.mapNotNull { o -> o.obj()?.let { p -> p["t"].num()?.let { t -> p["bpm"].num()?.let { t to it } } } }
            ?.sortedBy { it.first }
        rhr = trend(Api.trends(ctx, "rhr", 30))
        hrv = trend(Api.trends(ctx, "hrv", 30))
        loaded = true
    }

    val c = AppTheme.colors
    LargeTitleScaffold("Heart") {
        item { Section("Now") { MeasureNow() } }
        if (!loaded) return@LargeTitleScaffold
        item {
            Section("Heart rate") {
                val pts = hr.orEmpty()
                if (pts.isEmpty()) Empty() else {
                    val t0 = pts.first().first
                    Text(
                        "Latest", style = AppType.footnote, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
                    )
                    Value(pts.last().second, "bpm", c.heart)
                    Text(
                        "Last 24 h · ${pts.minOf { it.second }.roundToInt()}–${pts.maxOf { it.second }.roundToInt()} bpm",
                        style = AppType.subhead, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = AppSpace.l),
                    )
                    // x = 5 min slots since the first point; labels land on 12 AM / 6 AM / 12 PM / 6 PM.
                    val zone = ZoneId.systemDefault()
                    val boundary = Instant.ofEpochSecond(t0.toLong()).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .let { z -> generateSequence(z.plusHours(1)) { it.plusHours(1) }.first { it.hour % 6 == 0 } }
                    val offset = ceil((boundary.toEpochSecond() - t0) / SLOT_S).toInt()
                    val fmt = SimpleDateFormat("h a", Locale.getDefault())
                    Chart(
                        listOf(pts.map { (it.first - t0) / SLOT_S to it.second }), c.heart, true, 72, offset,
                    ) { x -> fmt.format(Date(((t0 + x * SLOT_S) * 1000).toLong())) }
                }
            }
        }
        item { TrendCard("Resting heart rate · 30 days", rhr, "bpm", c.heart) }
        item { TrendCard("Heart rate variability · 30 days", hrv, "ms", c.hrv) }
    }
}

@Composable
private fun TrendCard(title: String, t: Trend?, unit: String, color: Color) {
    val c = AppTheme.colors
    Section(title) {
        if (t == null || t.value.isEmpty()) return@Section Empty()
        Text(
            "Latest", style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
        )
        Value(t.value.last().second, unit, color)
        t.base.lastOrNull()?.let {
            Text(
                "Baseline ${it.second.roundToInt()} $unit", style = AppType.subhead, color = c.secondaryLabel,
                modifier = Modifier.padding(start = AppSpace.l),
            )
        }
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        Chart(listOf(t.value, t.base), color, false, 7, 0) { x -> t.first.plusDays(x.toLong()).format(fmt) }
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text(title, style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard(content = content)
    }
}

/** On-demand live reading: connects to the ring, streams ~12-30 s, shows the bpm. Leaving the tab cancels it. */
@Composable
private fun MeasureNow() {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var bpm by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("Tap to read your heart rate from the ring.") }
    val sweep = remember { Animatable(0f) }
    val measuring = job?.isActive == true

    fun start() {
        if (measuring) return
        val address = ctx.ringAddress
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        status = when {
            address.isNullOrBlank() -> return run { status = "Set the ring address in Settings first." }
            !granted -> return run { status = "Allow Bluetooth for Ring Health first (Settings > Apps)." }
            else -> "Connecting to ring…"
        }
        bpm = null
        job = scope.launch {
            launch { sweep.snapTo(0f); sweep.animateTo(1f, tween(30_000, easing = LinearEasing)) }
            val result = runLiveHr(ctx.applicationContext, address!!, onBpm = { bpm = it }) { p ->
                status = if (p.startsWith("♥")) "Measuring… keep your hand still" else p
            }
            status = if (result.startsWith("♥")) "Just now" else result
            sweep.animateTo(if (result.startsWith("♥")) 1f else 0f, tween(300))
            job = null
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(AppSpace.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpace.m),
    ) {
        ScoreRing(sweep.value, c.heart, Modifier.size(160.dp), stroke = 12.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bpm?.toString() ?: "—", style = AppType.largeTitle, color = if (bpm == null) c.secondaryLabel else c.label)
                Text("bpm", style = AppType.footnote, color = c.secondaryLabel)
            }
        }
        Text(status, style = AppType.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AppShape.small)
                .background(if (measuring) c.background else c.heart.copy(alpha = 0.12f))
                .clickable(enabled = !measuring, onClick = ::start),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (measuring) "Measuring…" else if (bpm != null) "Measure again" else "Measure now",
                style = AppType.headline, color = if (measuring) c.secondaryLabel else c.heart)
        }
    }
}

@Composable
private fun Empty() {
    Text("Not enough data yet", style = AppType.subhead, color = AppTheme.colors.secondaryLabel, modifier = Modifier.padding(AppSpace.l))
}

@Composable
private fun Value(v: Double, unit: String, color: Color) {
    Text(
        buildAnnotatedString {
            append(v.roundToInt().toString())
            withStyle(SpanStyle(fontSize = 17.sp, color = AppTheme.colors.secondaryLabel)) { append(" $unit") }
        },
        style = AppType.largeTitle, modifier = Modifier.padding(start = AppSpace.l),
    )
}

/** [series] 0 is the main line with a light area fill; series 1 (optional) is a wide translucent band. */
@Composable
private fun Chart(
    series: List<List<Pair<Double, Double>>>, color: Color, area: Boolean, spacing: Int, offset: Int, label: (Double) -> String,
) {
    val c = AppTheme.colors
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            lineSeries { series.filter { it.isNotEmpty() }.forEach { s -> series(s.map { it.first }, s.map { it.second }) } }
        }
    }
    val main = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color)),
        areaFill = if (area) LineCartesianLayer.AreaFill.single(fill(color.copy(alpha = 0.15f))) else null,
        pointConnector = LineCartesianLayer.PointConnector.cubic(),
    )
    val band = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color.copy(alpha = 0.18f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 22f),
    )
    val ys = series.flatten().map { it.second }
    val span = ys.max() - ys.min()
    val step = if (span > 60) 20.0 else if (span > 24) 10.0 else 5.0
    val lo = floor((ys.min() - span * 0.1) / step) * step
    val hi = ceil((ys.max() + span * 0.1) / step) * step
    CartesianChartHost(
        rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(main, band),
                rangeProvider = CartesianLayerRangeProvider.fixed(minY = lo, maxY = maxOf(hi, lo + step)),
            ),
            startAxis = VerticalAxis.rememberStart(
                itemPlacer = VerticalAxis.ItemPlacer.step({ step }),
                guideline = rememberLineComponent(fill = fill(c.separator), thickness = 0.5.dp),
                label = rememberAxisLabelComponent(color = c.secondaryLabel),
                line = null, tick = null,
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = rememberAxisLabelComponent(color = c.secondaryLabel),
                guideline = null, line = null, tick = null,
                valueFormatter = CartesianValueFormatter { _, x, _ -> label(x) },
                itemPlacer = HorizontalAxis.ItemPlacer.aligned({ spacing }, { offset }),
            ),
        ),
        producer,
        Modifier.fillMaxWidth().height(180.dp).padding(AppSpace.l),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = remember { Zoom.Content }),
    )
}
