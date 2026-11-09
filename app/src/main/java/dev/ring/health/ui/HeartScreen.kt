package dev.ring.health.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Paint
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import kotlinx.serialization.json.contentOrNull
import java.time.LocalTime

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

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
    if (backend != true) return NoBackendTab("Heart", backend)
    val ctx = LocalContext.current
    var hr by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var rhr by remember { mutableStateOf<Trend?>(null) }
    var hrv by remember { mutableStateOf<Trend?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var doses by remember { mutableStateOf<List<Long>>(emptyList()) }
    var windowH by remember { mutableStateOf(6.0) }
    var medStarted by remember { mutableStateOf<String?>(null) }
    var med by remember { mutableStateOf<JsonObject?>(null) }
    var rhr90 by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var day by remember { mutableStateOf<JsonObject?>(null) }
    var workouts by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var journalWorkout by remember { mutableStateOf<Set<String>>(emptySet()) } // dates with a journal "workout"
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var bp by remember { mutableStateOf<List<Triple<LocalDate, Int, Int>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun loadDay() {
        hr = (Api.hr(ctx, date).obj()?.get("points") as? JsonArray)
            ?.mapNotNull { o -> o.obj()?.let { p -> p["t"].num()?.let { t -> p["bpm"].num()?.let { t to it } } } }
            ?.sortedBy { it.first }
        day = Api.today(ctx, date).obj()
        workouts = (day?.get("workouts") as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
    }
    suspend fun load() {
        rhr = trend(Api.trends(ctx, "rhr", 30))
        hrv = trend(Api.trends(ctx, "hrv", 30))
        Api.settings(ctx)?.let { s ->
            s["medication_window_h"].num()?.let { windowH = it }
            medStarted = s["medication_started"].str()?.takeIf { it.isNotBlank() }
        }
        val es = (Api.journal(ctx, 30).obj()?.get("entries") as? JsonArray)?.mapNotNull { it.obj() }.orEmpty().reversed() // oldest first
        journalWorkout = es.filter { it["kind"].str() == "workout" }.mapNotNull { it["date"].str() }.toSet()
        doses = es.filter { it["kind"].str() == "medication" }.mapNotNull {
            runCatching { LocalDate.parse(it["date"].str()).atTime(LocalTime.parse(it["value"].str())).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
        }
        // One point per day (the last reading): a line series needs distinct x values.
        bp = es.filter { it["kind"].str() == "bp" }.mapNotNull { e ->
            val v = e["value"].str()?.split("/")?.mapNotNull { it.toIntOrNull() }
            if (v?.size == 2) Triple(LocalDate.parse(e["date"].str()), v[0], v[1]) else null
        }.associateBy { it.first }.values.toList()
        if (medStarted != null) {
            med = Api.insights(ctx).obj()?.get("kinds").obj()?.get("medication").obj()
            rhr90 = (Api.trends(ctx, "rhr", 90).obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
        }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(date) { loadDay() }

    val c = AppTheme.colors
    val inputs = day?.get("inputs").obj()
    LargeTitleScaffold("Heart", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load(); loadDay() } finally { refreshing = false } }
    }) {
        item { Section("Now") { MeasureNow() } }
        if (!loaded) return@LargeTitleScaffold
        item { DayPicker(date) { date = it } }
        item {
            Section("Heart rate") {
                val pts = hr.orEmpty()
                if (pts.isEmpty()) Empty() else {
                    val t0 = date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toDouble()
                    Text(
                        if (date == LocalDate.now()) "Latest" else "Last", style = AppType.footnote, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
                    )
                    Value(pts.last().second, "bpm", c.heart)
                    Text(
                        "${dayLabel(date)} · ${pts.minOf { it.second }.roundToInt()}–${pts.maxOf { it.second }.roundToInt()} bpm",
                        style = AppType.subhead, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = AppSpace.l),
                    )
                    // x = 5 min slots since midnight (0..287); labels land on 12 AM / 6 AM / 12 PM / 6 PM.
                    // Doses whose active window reaches into the day, in chart x.
                    val now = System.currentTimeMillis() / 1000
                    val shown = doses.filter { it <= now && it < t0 + 86400 && it + windowH * 3600 > t0 }
                    val windows = shown.map { (it - t0) / SLOT_S to (it + windowH * 3600 - t0) / SLOT_S }
                    val xy = pts.map { (it.first - t0) / SLOT_S to it.second }
                    val byX = xy.toMap()
                    val spans = workouts.mapNotNull { w -> w["start"].num()?.let { s -> w["end"].num()?.let { e -> (s - t0) / SLOT_S to (e - t0) / SLOT_S } } }
                    Chart(
                        listOf(xy), c.heart, true, 72, 0, x = 0.0..287.0,
                        decorations = listOfNotNull(
                            spans.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.heart.toArgb(), alpha = 31, line = false) },
                            windows.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.heart.toArgb()) },
                        ),
                        marker = { x -> "${byX[x]?.roundToInt()} bpm · ${clock(t0 + x * SLOT_S)}" },
                    ) { x -> clock(t0 + x * SLOT_S).replace(":00 ", " ") }
                    shown.forEach { t ->
                        Text(
                            "Dose ${clock(t.toDouble())} · active until ~${clock(t + windowH * 3600.0)}", style = AppType.footnote, color = c.secondaryLabel,
                            modifier = Modifier.padding(start = AppSpace.l, bottom = AppSpace.s),
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                listOf("Resting HR" to ("rhr" to "bpm"), "HRV" to ("hrv" to "ms")).forEach { (label, m) ->
                    val v = inputs?.get(m.first).num()
                    InsetCard(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(AppSpace.l)) {
                            Text(label, style = AppType.footnote, color = c.secondaryLabel)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(v?.roundToInt()?.toString() ?: "—", style = AppType.title)
                                if (v != null) Text(" ${m.second}", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        if (workouts.isNotEmpty()) item {
            val cardio = day?.get("cardio").obj()
            val mins = (cardio?.get("minutes") as? JsonArray)?.sumOf { it.num() ?: 0.0 }?.roundToInt() ?: 0
            Section("Workouts") {
                Text(
                    "Cardio load ${cardio?.get("load").num()?.roundToInt() ?: "—"} · $mins min in zones", style = AppType.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.m, bottom = AppSpace.m),
                )
                workouts.forEach { w ->
                    Hairline(Modifier.padding(start = AppSpace.l))
                    WorkoutRow(w) { editing = w }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                Box(Modifier.padding(AppSpace.l)) {
                    if (date.toString() in journalWorkout) Text("In your journal", style = AppType.footnote, color = c.secondaryLabel)
                    else TintButton("Add to journal") {
                        scope.launch { if (Api.addJournal(ctx, date.toString(), "workout") != null) journalWorkout = journalWorkout + date.toString() }
                    }
                }
            }
        }
        item { TrendCard("Resting heart rate · 30 days", rhr, "bpm", c.heart) }
        item { TrendCard("Heart rate variability · 30 days", hrv, "ms", c.hrv) }
        medStarted?.let { st -> item { MedicationCard(med, st, rhr90) } }
        if (bp.isNotEmpty()) item { BloodPressureCard(bp) }
    }
    editing?.let { w ->
        val cur = w["kind"].str()
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = c.card,
            title = { Text("What was this?") },
            text = {
                Column {
                    KINDS.forEach { (k, name) ->
                        Text(
                            name + if (k == cur) "  ✓" else "",
                            style = AppType.body, color = if (k == cur) c.tint else c.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                editing = null
                                val start = w["start"].num()?.toLong() ?: return@clickable
                                scope.launch {
                                    (Api.setWorkoutKind(ctx, date, start, k)?.get("workouts") as? JsonArray)?.let { a -> workouts = a.mapNotNull { it.obj() } }
                                }
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
}

private val KINDS = listOf("walk" to "Walk", "ride" to "Ride", "workout" to "Workout")

/** "Walk · 42 min" / avg bpm, then "2:10–2:52 PM · max 141 · load 6", " · guessed" until the user confirms the kind. */
@Composable
private fun WorkoutRow(w: JsonObject, onClick: () -> Unit) {
    val c = AppTheme.colors
    val kind = w["kind"].str()
    val icon = when (kind) { "walk" -> Icons.Rounded.DirectionsWalk; "ride" -> Icons.Rounded.DirectionsBike; else -> Icons.Rounded.FitnessCenter }
    val a = clock(w["start"].num() ?: 0.0)
    val b = clock(w["end"].num() ?: 0.0)
    // "2:10–2:52 PM": drop the start's AM/PM when both share it.
    val span = (if (!a.last().isDigit() && a.takeLast(3) == b.takeLast(3)) a.dropLast(3) else a) + "–" + b
    val guessed = if (w["confirmed"].str() == "true") "" else " · guessed"
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = AppSpace.l, top = AppSpace.m, bottom = AppSpace.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.heart, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = AppSpace.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${KINDS.firstOrNull { it.first == kind }?.second ?: "Workout"} · ${duration(w["minutes"].num()?.roundToInt() ?: 0)}", style = AppType.body, modifier = Modifier.weight(1f))
                Text("avg ${w["avg_bpm"].num()?.roundToInt()} bpm", style = AppType.subhead, color = c.secondaryLabel)
            }
            Text(
                "$span · max ${w["max_bpm"].num()?.roundToInt()} · load ${w["load"].num()?.roundToInt()}$guessed",
                style = AppType.footnote, color = c.secondaryLabel,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel, modifier = Modifier.padding(end = AppSpace.s))
    }
}

/** Same as Today's tinted button (private there). */
@Composable
private fun TintButton(text: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Text(
        text, style = AppType.headline, color = c.tint, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(AppShape.small).background(c.tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(vertical = AppSpace.m),
    )
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
        val byX = t.value.toMap()
        Chart(
            listOf(t.value, t.base), color, false, 7, 0,
            marker = { x -> "${byX[x]?.roundToInt()?.let { "$it $unit" } ?: "—"} · ${t.first.plusDays(x.toLong()).format(fmt)}" },
        ) { x -> t.first.plusDays(x.toLong()).format(fmt) }
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
    // Ring breathes 1 -> 1.03 while measuring; still under reduce-motion.
    val pulse = remember { Animatable(1f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(measuring) {
        if (!measuring || reduce) { pulse.animateTo(1f); return@LaunchedEffect }
        val soft = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessVeryLow)
        while (true) { pulse.animateTo(1.03f, soft); pulse.animateTo(1f, soft) }
    }

    fun start() {
        if (measuring) return
        val address = ctx.ringAddress
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        status = when {
            address.isNullOrBlank() -> return run { status = "Set the ring address in Settings first." }
            !granted -> return run { status = "Allow Bluetooth for Circa first (Settings > Apps)." }
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
        ScoreRing(sweep.value, c.heart, Modifier.size(160.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }, stroke = 12.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bpm?.let { CountUp(it, AppType.largeTitle) } ?: Text("—", style = AppType.largeTitle, color = c.secondaryLabel)
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
private fun Empty() = EmptyState(Icons.Rounded.MonitorHeart, "Not enough data yet", "Keep the ring on; this fills in after a few syncs.")

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

/** [series] 0 is the main line with a faded area fill; series 1 (optional) is a wide translucent band. */
@Composable
private fun Chart(
    series: List<List<Pair<Double, Double>>>, color: Color, area: Boolean, spacing: Int, offset: Int,
    second: Color? = null, x: ClosedFloatingPointRange<Double>? = null, decorations: List<Decoration> = emptyList(), marker: (Double) -> CharSequence, label: (Double) -> String,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            lineSeries { series.filter { it.isNotEmpty() }.forEach { s -> series(s.map { it.first }, s.map { it.second }) } }
        }
    }
    val main = chartLine(color, area)
    val band = if (second != null) chartLine(second) else LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color.copy(alpha = 0.18f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 22f),
    )
    val ys = series.flatten().map { it.second }
    val span = ys.max() - ys.min()
    val step = if (span > 60) 20.0 else if (span > 24) 10.0 else 5.0
    val lo = floor((ys.min() - span * 0.1) / step) * step
    val hi = ceil((ys.max() + span * 0.1) / step) * step
    ScrubChartHost(
        rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(main, band),
                rangeProvider = CartesianLayerRangeProvider.fixed(minX = x?.start, maxX = x?.endInclusive, minY = lo, maxY = maxOf(hi, lo + step)),
            ),
            startAxis = startAxis(step),
            bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> label(x) }, spacing, offset),
            marker = rememberScrubMarker(color, marker),
            decorations = decorations,
        ),
        producer, 180.dp,
    )
}

/** Spans shaded in [color] at [alpha] (8 % doses, 12 % workouts), a line at each start when [line]; [windows] are (start, end) in chart x. */
private class DoseWindows(private val windows: List<Pair<Double, Double>>, private val color: Int, private val alpha: Int = 20, private val line: Boolean = true) : Decoration {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawUnderLayers(context: CartesianDrawingContext) = with(context) {
        val b = layerBounds
        fun px(x: Double) = b.left + layerDimensions.startPadding + ((x - ranges.minX) / ranges.xStep).toFloat() * layerDimensions.xSpacing - scroll
        windows.forEach { (dose, end) ->
            paint.color = color
            paint.alpha = alpha
            canvas.drawRect(px(dose).coerceIn(b.left, b.right), b.top, px(end).coerceIn(b.left, b.right), b.bottom, paint)
            if (line && dose >= ranges.minX && dose <= ranges.maxX) {
                paint.alpha = 200
                paint.strokeWidth = dpToPx(1.5f)
                canvas.drawLine(px(dose), b.top, px(dose), b.bottom, paint)
            }
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
            Text("Averages from your own data. Other things may play a part. Not medical advice.", style = AppType.footnote, color = c.secondaryLabel)
        }
    }
}

@Composable
private fun BloodPressureCard(pts: List<Triple<LocalDate, Int, Int>>) {
    val c = AppTheme.colors
    val last = pts.last()
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    Section("Blood pressure · 30 days") {
        Text(
            "Latest · ${last.first.format(fmt)}", style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
        )
        Text(
            buildAnnotatedString {
                append("${last.second}/${last.third}")
                withStyle(SpanStyle(fontSize = 17.sp, color = c.secondaryLabel)) { append(" mmHg") }
            },
            style = AppType.largeTitle, modifier = Modifier.padding(start = AppSpace.l),
        )
        Row(Modifier.padding(start = AppSpace.l), horizontalArrangement = Arrangement.spacedBy(AppSpace.m), verticalAlignment = Alignment.CenterVertically) {
            listOf("Systolic" to c.heart, "Diastolic" to c.hrv).forEach { (name, col) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                    Text(name, style = AppType.footnote, color = c.secondaryLabel)
                }
            }
        }
        val first = pts.first().first
        val byX = pts.associateBy { (it.first.toEpochDay() - first.toEpochDay()).toDouble() }
        if (pts.size < 2) Text("Log another reading to see the trend.", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l))
        else Chart(
            listOf(pts.map { (it.first.toEpochDay() - first.toEpochDay()).toDouble() to it.second.toDouble() },
                pts.map { (it.first.toEpochDay() - first.toEpochDay()).toDouble() to it.third.toDouble() }),
            c.heart, false, 7, 0, second = c.hrv,
            marker = { x -> "${byX[x]?.let { "${it.second}/${it.third} mmHg" } ?: "—"} · ${first.plusDays(x.toLong()).format(fmt)}" },
        ) { x -> first.plusDays(x.toLong()).format(fmt) }
        Text(
            "From the cuff readings in your journal.", style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.l),
        )
    }
}
