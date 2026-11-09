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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.contentOrNull
import java.time.LocalTime

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private const val SLOT_S = 300.0

@Composable
fun HeartScreen(backend: Boolean?, header: @Composable () -> Unit) {
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
    var nextInputs by remember { mutableStateOf<JsonObject?>(null) } // the night that starts on [date] belongs to the next wake date
    var workouts by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var breaths by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var bp by remember { mutableStateOf<List<Triple<LocalDate, Int, Int>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun loadDay() {
        Api.hr(ctx, date).obj()?.let { hrDay ->
            breaths = (hrDay["breaths"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
            hr = (hrDay["points"] as? JsonArray)
                ?.mapNotNull { o -> o.obj()?.let { p -> p["t"].num()?.let { t -> p["bpm"].num()?.let { t to it } } } }
                ?.sortedBy { it.first }
        }
        Api.today(ctx, date).obj()?.let { d ->
            day = d
            workouts = (d["workouts"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
        }
        if (date < LocalDate.now()) nextInputs = Api.today(ctx, date.plusDays(1)).obj()?.get("inputs").obj()
    }
    suspend fun load() {
        trend(Api.trends(ctx, "rhr", 30))?.let { rhr = it }
        trend(Api.trends(ctx, "hrv", 30))?.let { hrv = it }
        Api.settings(ctx)?.let { s ->
            s["medication_window_h"].num()?.let { windowH = it }
            medStarted = s["medication_started"].str()?.takeIf { it.isNotBlank() }
        }
        Api.journal(ctx, 30).obj()?.let { j ->
            val es = (j["entries"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty().reversed() // oldest first
            doses = es.filter { it["kind"].str() == "medication" }.mapNotNull {
                runCatching { LocalDate.parse(it["date"].str()).atTime(LocalTime.parse(it["value"].str())).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
            }
            // One point per day (the last reading): a line series needs distinct x values.
            bp = es.filter { it["kind"].str() == "bp" }.mapNotNull { e ->
                val v = e["value"].str()?.split("/")?.mapNotNull { it.toIntOrNull() }
                if (v?.size == 2) Triple(LocalDate.parse(e["date"].str()), v[0], v[1]) else null
            }.associateBy { it.first }.values.toList()
        }
        if (medStarted != null) {
            Api.insights(ctx).obj()?.let { med = it["kinds"].obj()?.get("medication").obj() }
            (Api.trends(ctx, "rhr", 90).obj()?.get("points") as? JsonArray)?.let { a -> rhr90 = a.mapNotNull { it.obj() } }
        }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(date) { hr = null; breaths = emptyList(); day = null; nextInputs = null; workouts = emptyList(); loadDay() }

    val c = AppTheme.colors
    val inputs = day?.get("inputs").obj()
    LargeTitleScaffold("Heart", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load(); loadDay() } finally { refreshing = false } }
    }) {
        item { Box(Modifier.padding(bottom = AppSpace.m)) { header() } }
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
                    val breathSpans = breaths.mapNotNull { w -> w["start"].num()?.let { s -> w["end"].num()?.let { e -> (s - t0) / SLOT_S to (e - t0) / SLOT_S } } }
                    // "Settling" to asleep; inputs are minutes after noon of the evening before the wake date (noon = slot 144).
                    val settling = listOf(inputs to -144.0, nextInputs to 144.0).mapNotNull { (n, off) ->
                        n?.get("wind_down").num()?.let { w -> n?.get("bed_start").num()?.takeIf { it > w }?.let { b -> off + w / 5 to off + b / 5 } }
                    }.filter { it.second > 0 && it.first < 288 }
                    Chart(
                        listOf(xy), c.heart, true, 72, 0, x = 0.0..287.0,
                        decorations = listOfNotNull(
                            settling.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.sleepBand.toArgb(), alpha = (c.sleepBand.alpha * 255).roundToInt(), line = false) },
                            spans.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.heart.toArgb(), alpha = 31, line = false) },
                            // A 2-min session is under 1 px wide on the 24 h axis: the start line makes it visible.
                            breathSpans.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.breathe.toArgb(), alpha = 31) },
                            windows.takeIf { it.isNotEmpty() }?.let { DoseWindows(it, c.heart.toArgb()) },
                        ),
                        marker = { x -> "${byX[x]?.roundToInt()} bpm · ${clock(t0 + x * SLOT_S)}" },
                    ) { x -> clock(t0 + x * SLOT_S).replace(":00 ", " ") }
                    settling.forEach { (a, b) ->
                        Text(
                            "Settling ${clock(t0 + a * SLOT_S)} · asleep ${clock(t0 + b * SLOT_S)}", style = AppType.footnote, color = c.secondaryLabel,
                            modifier = Modifier.padding(start = AppSpace.l, bottom = AppSpace.s),
                        )
                    }
                    shown.forEach { t ->
                        Text(
                            "Dose ${clock(t.toDouble())} · active until ~${clock(t + windowH * 3600.0)}", style = AppType.footnote, color = c.secondaryLabel,
                            modifier = Modifier.padding(start = AppSpace.l, bottom = AppSpace.s),
                        )
                    }
                }
            }
        }
        if (breaths.isNotEmpty()) item { BreathingCard(breaths) }
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
        item { TrendCard("Resting heart rate · 30 days", rhr, "bpm", c.heart) }
        item { TrendCard("Heart rate variability · 30 days", hrv, "ms", c.hrv) }
        medStarted?.let { st -> item { MedicationCard(med, st, rhr90) } }
        if (bp.isNotEmpty()) item { BloodPressureCard(bp) }
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

/** One row per breathing session; tap shows its heart rate (x = seconds since start). */
@Composable
private fun BreathingCard(breaths: List<JsonObject>) {
    val c = AppTheme.colors
    Section("Breathing") {
        breaths.forEachIndexed { i, b ->
            val start = b["start"].num() ?: return@forEachIndexed
            val secs = ((b["end"].num() ?: start) - start).roundToInt()
            var open by remember(start) { mutableStateOf(false) }
            if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                Text("${clock(start)} · ${b["rounds"].num()?.roundToInt() ?: 0} rounds · ${secs / 60}:%02d".format(secs % 60), style = AppType.body)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                    val hr0 = b["hr_start"].num()
                    val hr1 = b["hr_end"].num()
                    val s0 = b["stress_before"].num()
                    val s1 = b["stress_after"].num()
                    Text(
                        (if (hr0 != null && hr1 != null) "HR ${hr0.roundToInt()} → ${hr1.roundToInt()}" else "HR —") +
                            if (s0 != null && s1 != null) " · stress ${s0.roundToInt()} → ${s1.roundToInt()}" else "",
                        style = AppType.subhead, color = c.secondaryLabel,
                    )
                    listOf("feel_before", "feel_after").mapNotNull { b[it].num()?.roundToInt()?.let { f -> FACES.getOrNull(f - 1) } }.forEach {
                        Icon(it, null, tint = c.breathe, modifier = Modifier.size(16.dp))
                    }
                }
            }
            val pts = (b["points"] as? JsonArray)?.mapNotNull { o -> o.obj()?.let { p -> p["t"].num()?.let { t -> p["bpm"].num()?.let { (t - start).roundToInt().toDouble() to it } } } }
                ?.distinctBy { it.first }?.sortedBy { it.first }.orEmpty()
            if (open && pts.isNotEmpty()) {
                val byX = pts.toMap() // points are 5 s apart (backend step): label every 30 s
                Chart(listOf(pts), c.breathe, true, 6, 0, height = 120.dp, marker = { x -> "${byX[x]?.roundToInt()} bpm · ${x.roundToInt()} s" }) { x -> "${x.roundToInt()} s" }
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
