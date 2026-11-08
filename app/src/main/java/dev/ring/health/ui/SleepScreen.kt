package dev.ring.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import dev.ring.health.Prefs.sleepNeedHours
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private class Seg(val startS: Double, val endS: Double, val stage: Int)
private class HrPt(val t: Double, val bpm: Double)

private val STAGE_NAME = mapOf(4 to "Awake", 3 to "REM", 1 to "Light", 2 to "Deep")

/** "1:10 – 1:45 PM": AM/PM once when both ends share it (24 h: "13:10 – 13:45"). */
private fun span(a: Double, b: Double): String {
    val x = clock(a)
    val y = clock(b)
    val ap = y.substringAfterLast(' ', "")
    return (if (ap.isNotEmpty() && x.endsWith(" $ap")) x.removeSuffix(" $ap") else x) + " – " + y
}

/** Local minutes after noon, as the backend's _after_noon. */
private fun afterNoon(sec: Double): Int = Instant.ofEpochSecond(sec.toLong()).atZone(ZoneId.systemDefault()).let { (it.hour * 60 + it.minute + 720) % 1440 }
private fun noonClock(min: Double): String = clock(LocalTime.NOON.plusMinutes(min.roundToInt().toLong()))
private fun median(v: List<Int>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }

/** A segment runs until the next sample with a different stage; stage 0 ends the night. */
private fun segments(stages: JsonArray?, endS: Double): List<Seg> {
    val pts = stages?.mapNotNull { s -> s.obj()?.let { o -> o["t"].num()?.let { t -> t to (o["stage"].num()?.toInt() ?: 0) } } }
        ?.sortedBy { it.first } ?: return emptyList()
    val out = mutableListOf<Seg>()
    pts.forEachIndexed { i, (t, st) ->
        val prev = pts.getOrNull(i - 1)
        if (st !in 1..4 || prev?.second == st) return@forEachIndexed
        val next = pts.drop(i + 1).firstOrNull { it.second != st }?.first ?: endS
        out += Seg(t, next, st)
    }
    return out
}

@Composable
fun SleepScreen(backend: Boolean?) {
    if (backend != true) return NoBackendTab("Sleep", backend)
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var trend by remember { mutableStateOf<List<Pair<String, Double?>>?>(null) }
    var week by remember { mutableStateOf<List<Pair<LocalDate, JsonObject?>>>(emptyList()) }
    val producer = remember { CartesianChartModelProducer() }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val need = remember { ctx.sleepNeedHours }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() {
        val days = (6 downTo 0).map { date.minusDays(it.toLong()) }
        val all = coroutineScope { days.map { day -> async { Api.sleep(ctx, day.toString()).obj() } }.awaitAll() }
        week = days.zip(all.map { it?.get("session").obj() })
        data = all.last()
        val t = week.map { (day, s) -> day.toString() to s?.get("total_min").num() }
        trend = t
        producer.runTransaction { columnSeries { series(t.map { (it.second ?: 0.0) / 60 }) } }
    }
    LaunchedEffect(date) { load() }

    val c = AppTheme.colors
    LargeTitleScaffold("Sleep", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        val d = data ?: return@LargeTitleScaffold
        item { DayPicker(date) { date = it } }
        val ses = d["session"].obj()
        if (ses == null) item {
            InsetCard {
                if (date == LocalDate.now()) EmptyState(Icons.Rounded.Bedtime, "No sleep recorded", "Wear the ring to bed; last night appears after the morning sync.")
                else EmptyState(Icons.Rounded.Bedtime, "No sleep recorded for this night", "Wear the ring to bed and sync in the morning.")
            }
        } else {
            val startS = ses["start"].num() ?: 0.0
            val endS = ses["end"].num() ?: startS
            val total = ses["total_min"].num() ?: 0.0
            val awake = ses["awake_min"].num() ?: 0.0
            val rem = ses["rem_min"].num() ?: 0.0
            val deep = ses["deep_min"].num() ?: 0.0
            val score = d["score"].num()
            val naps = (d["naps"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
            val napMin = naps.sumOf { it["total_min"].num() ?: 0.0 }
            val hr = (d["hr"] as? JsonArray)?.mapNotNull { it.obj()?.hrPt() }.orEmpty()
            item {
                InsetCard {
                    Row(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.nightTop, c.nightBottom))).padding(AppSpace.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Time asleep", style = AppType.subhead, color = c.secondaryLabel)
                            Text(
                                buildAnnotatedString {
                                    val unit = SpanStyle(fontSize = 17.sp, color = c.secondaryLabel)
                                    append((total / 60).toInt().toString()); withStyle(unit) { append(" h ") }
                                    append((total % 60).roundToInt().toString()); withStyle(unit) { append(" min") }
                                },
                                style = AppType.largeTitle,
                            )
                            Text("${clock(startS)} – ${clock(endS)}", style = AppType.subhead, color = c.secondaryLabel)
                            if (napMin > 0) Text("+${napMin.roundToInt()} min nap", style = AppType.subhead, color = c.sleep)
                        }
                        ScoreRing((score ?: 0.0).toFloat() / 100f, c.sleep, Modifier.size(64.dp), stroke = 7.dp) {
                            Text(score?.roundToInt()?.toString() ?: "—", style = AppType.headline)
                        }
                    }
                    naps.forEach { n ->
                        Hairline(Modifier.padding(start = AppSpace.l))
                        Text(
                            "Nap ${span(n["start"].num() ?: 0.0, n["end"].num() ?: 0.0)} · ${(n["total_min"].num() ?: 0.0).roundToInt()} min",
                            style = AppType.body, modifier = Modifier.padding(AppSpace.l),
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    Text("Stages", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                    InsetCard { Hypnogram(segments(d["stages"] as? JsonArray, endS), startS, endS, hr, d["hr_low"].obj()?.hrPt()) }
                }
            }
            item {
                val light = (total - rem - deep).coerceAtLeast(0.0)
                val stages = listOf(
                    "Awake" to (awake to c.stageAwake), "REM" to (rem to c.stageRem),
                    "Light" to (light to c.stageCore), "Deep" to (deep to c.stageDeep),
                )
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Time in stages", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    // One stacked bar of the night, then the list.
                    Row(Modifier.fillMaxWidth().padding(AppSpace.l).height(12.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        stages.filter { it.second.first > 0 }.forEach { (_, v) -> Box(Modifier.weight(v.first.toFloat()).fillMaxHeight().background(v.second)) }
                    }
                    stages.forEach { (name, v) ->
                        Hairline(Modifier.padding(start = AppSpace.l))
                        Row(Modifier.fillMaxWidth().padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(v.second, CircleShape))
                            Text(name, style = AppType.body, modifier = Modifier.weight(1f).padding(start = AppSpace.m))
                            Text(duration(v.first.roundToInt()), style = AppType.body, color = c.secondaryLabel)
                        }
                    }
                }
                }
            }
            item { Regularity(d["contributors"].obj()?.get("regularity").num(), week, d["sleep_extra"].obj()?.get("regularity").obj()) }
            if (d.containsKey("sleep_extra")) item { BloodOxygen(d["sleep_extra"].obj()?.get("spo2").obj(), date != LocalDate.now()) }
        }
        val t = trend ?: return@LargeTitleScaffold
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Last 7 nights", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    Text(
                        "Hours asleep · goal $need h", style = AppType.footnote, color = c.secondaryLabel,
                        modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
                    )
                    val day = { x: Double -> runCatching { LocalDate.parse(t[x.toInt()].first).dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()) }.getOrDefault("") }
                    ScrubChartHost(
                        rememberCartesianChart(
                            rememberColumnCartesianLayer(
                                ColumnCartesianLayer.ColumnProvider.series(
                                    rememberLineComponent(fill = fill(c.sleep), thickness = 18.dp, shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30)),
                                ),
                                rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxOf(9.0, need + 1.5)),
                            ),
                            startAxis = startAxis(3.0),
                            bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> day(x) }),
                            marker = rememberScrubMarker(c.sleep) { x ->
                                val min = t.getOrNull(x.toInt())?.second?.toInt() ?: 0
                                "${duration(min)} · ${day(x)}"
                            },
                            decorations = listOf(dashedLine(need.toDouble(), c.secondaryLabel)),
                        ),
                        producer, 200.dp,
                    )
                }
            }
        }
    }
}

private fun JsonObject.hrPt(): HrPt? = this["t"].num()?.let { t -> this["bpm"].num()?.let { HrPt(t, it) } }

@Composable
private fun Hypnogram(segs: List<Seg>, startS: Double, endS: Double, hr: List<HrPt>, low: HrPt?) {
    val c = AppTheme.colors
    val measurer = rememberTextMeasurer()
    val label = AppType.footnote.copy(color = c.secondaryLabel)
    val lanes = listOf("Awake" to 4, "REM" to 3, "Light" to 1, "Deep" to 2)
    val colors = mapOf(4 to c.stageAwake, 3 to c.stageRem, 1 to c.stageCore, 2 to c.stageDeep)
    val line = c.separator
    val hrH = if (hr.size > 1) 72.dp else 0.dp
    var touch by remember { mutableStateOf<Float?>(null) }
    Canvas(
        Modifier.fillMaxWidth().height(184.dp + hrH).padding(horizontal = AppSpace.l, vertical = AppSpace.m).pointerInput(Unit) {
            awaitEachGesture {
                touch = awaitFirstDown().position.x
                do {
                    val e = awaitPointerEvent()
                    e.changes.firstOrNull()?.let { touch = it.position.x }
                } while (e.changes.any { it.pressed })
                touch = null
            }
        },
    ) {
        val left = 48.dp.toPx()
        val laneH = 35.dp.toPx()
        val span = (endS - startS).coerceAtLeast(1.0)
        fun x(s: Double) = left + ((s - startS) / span * (size.width - left)).toFloat()
        lanes.forEachIndexed { i, (name, _) ->
            val cy = laneH * i + laneH / 2
            drawLine(line, Offset(left, cy), Offset(size.width, cy), 0.5.dp.toPx())
            val m = measurer.measure(name, label)
            drawText(m, topLeft = Offset(0f, cy - m.size.height / 2f))
        }
        val barH = 14.dp.toPx()
        segs.forEach { s ->
            val i = lanes.indexOfFirst { it.second == s.stage }
            val cy = laneH * i + laneH / 2
            val x0 = x(s.startS)
            val w = (x(s.endS) - x0).coerceAtLeast(3.dp.toPx())
            drawRoundRect(colors.getValue(s.stage), Offset(x0, cy - barH / 2), Size(w, barH), CornerRadius(barH / 2))
        }
        // Heart-rate lane under the stages, same x-axis, min–max scaled.
        if (hr.size > 1) {
            val top = laneH * 4
            val y0 = top + 8.dp.toPx()
            val y1 = top + hrH.toPx() - 22.dp.toPx()
            val lo = hr.minOf { it.bpm }
            val hi = hr.maxOf { it.bpm }.coerceAtLeast(lo + 1)
            fun y(b: Double) = y1 - ((b - lo) / (hi - lo) * (y1 - y0)).toFloat()
            drawLine(line, Offset(left, top), Offset(size.width, top), 0.5.dp.toPx())
            val m = measurer.measure("Heart", label)
            drawText(m, topLeft = Offset(0f, (y0 + y1) / 2 - m.size.height / 2f))
            val path = Path()
            hr.forEachIndexed { i, p ->  // a gap over 10 min (ring off) breaks the line
                if (i == 0 || p.t - hr[i - 1].t > 600) path.moveTo(x(p.t), y(p.bpm)) else path.lineTo(x(p.t), y(p.bpm))
            }
            drawPath(path, c.heart, style = Stroke(1.5.dp.toPx()))
            low?.let { p ->
                val o = Offset(x(p.t), y(p.bpm))
                drawCircle(c.card, 5.dp.toPx(), o)
                drawCircle(c.heart, 3.5.dp.toPx(), o)
                val lm = measurer.measure("Low ${p.bpm.roundToInt()} bpm · ${clock(p.t)}", label.copy(color = c.heart))
                drawText(lm, topLeft = Offset((o.x - lm.size.width / 2f).coerceIn(left, size.width - lm.size.width), y1 + 4.dp.toPx()))
            }
        }
        val step = if (span > 6 * 3600) 2 else 1
        var t = ceil(startS / 3600).toLong() * 3600
        var n = 0
        while (t <= endS) {
            if (n++ % step == 0) {
                val m = measurer.measure(clock(t.toDouble()).replace(":00 ", " "), label)
                val tx = (x(t.toDouble()) - m.size.width / 2f).coerceIn(left, size.width - m.size.width)
                drawText(m, topLeft = Offset(tx, size.height - m.size.height))
            }
            t += 3600
        }
        // Touch: hairline guide + floating "time · stage · bpm" label.
        touch?.let { tx ->
            val px = tx.coerceIn(left, size.width)
            val at = startS + (px - left) / (size.width - left) * span
            val stage = segs.firstOrNull { at >= it.startS && at < it.endS }?.let { STAGE_NAME[it.stage] }
            val bpm = hr.minByOrNull { abs(it.t - at) }?.takeIf { abs(it.t - at) <= 600 }
            val text = listOfNotNull(clock(at), stage, bpm?.let { "${it.bpm.roundToInt()} bpm" }).joinToString(" · ")
            drawLine(c.secondaryLabel, Offset(px, 0f), Offset(px, laneH * 4 + hrH.toPx()), 1.dp.toPx())
            val m = measurer.measure(text, label.copy(color = c.label))
            val pad = 6.dp.toPx()
            val w = m.size.width + pad * 2
            val bx = (px - w / 2).coerceIn(0f, size.width - w)
            drawRoundRect(c.background, Offset(bx, 0f), Size(w, m.size.height + pad), CornerRadius(8.dp.toPx()))
            drawText(m, topLeft = Offset(bx + pad, pad / 2))
        }
    }
}

/** 7-night bed→wake strip on a 6 PM–noon axis, median band behind it. */
@Composable
private fun Regularity(score: Double?, week: List<Pair<LocalDate, JsonObject?>>, reg: JsonObject?) {
    val c = AppTheme.colors
    val rows = week.map { (day, s) -> day to s?.let { afterNoon(it["start"].num() ?: 0.0) to afterNoon(it["end"].num() ?: 0.0) } }
    val have = rows.mapNotNull { it.second }
    if (have.isEmpty()) return
    // Medians and learning are the backend's (sleep_extra.regularity); the local median is only a fallback for older rows.
    val bed = reg?.get("bed_start_median").num() ?: median(have.map { it.first })
    val up = reg?.get("bed_end_median").num() ?: median(have.map { it.second })
    val nights = reg?.get("nights").num()?.toInt() ?: have.size
    val learning = if (reg != null) reg["sd_min"].num() == null else have.size < 4
    val measurer = rememberTextMeasurer()
    val label = AppType.footnote.copy(color = c.secondaryLabel)
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("Regularity", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = AppType.headline.fontWeight)) { append("Regularity" + (score?.let { " ${it.roundToInt()}" } ?: "")) }
                    withStyle(SpanStyle(color = c.secondaryLabel)) { append(" · bed around ${noonClock(bed)}, up around ${noonClock(up)}") }
                },
                style = AppType.subhead, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l, end = AppSpace.l),
            )
            if (learning) Text(
                "Learning your rhythm · $nights/4 nights. Regularity needs 4 of the last 7.",
                style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.xs, end = AppSpace.l),
            )
            Canvas(Modifier.fillMaxWidth().height((7 * 18 + 20 + 2 * 12).dp).padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
                val left = 48.dp.toPx()
                val rowH = 18.dp.toPx()
                val barH = 8.dp.toPx()
                fun x(m: Double) = left + ((m.coerceIn(360.0, 1440.0) - 360) / 1080 * (size.width - left)).toFloat()
                drawRoundRect(c.sleepBand, Offset(x(bed), 0f), Size(x(up) - x(bed), rowH * 7), CornerRadius(4.dp.toPx()))
                listOf(360.0, 720.0, 1080.0, 1440.0).forEach { m ->
                    drawLine(c.separator, Offset(x(m), 0f), Offset(x(m), rowH * 7), 0.5.dp.toPx())
                    val lm = measurer.measure(noonClock(m).replace(":00 ", " "), label)
                    drawText(lm, topLeft = Offset((x(m) - lm.size.width / 2f).coerceIn(left, size.width - lm.size.width), size.height - lm.size.height))
                }
                rows.forEachIndexed { i, (day, n) ->
                    val cy = rowH * i + rowH / 2
                    val dm = measurer.measure(day.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()), label)
                    drawText(dm, topLeft = Offset(0f, cy - dm.size.height / 2f))
                    n?.let { (a, b) ->
                        val x0 = x(a.toDouble())
                        drawRoundRect(c.sleep, Offset(x0, cy - barH / 2), Size((x(b.toDouble()) - x0).coerceAtLeast(barH), barH), CornerRadius(barH / 2))
                    }
                }
            }
        }
    }
}

@Composable
private fun BloodOxygen(sp: JsonObject?, past: Boolean) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("Blood oxygen", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            Column(Modifier.padding(AppSpace.l)) {
                if (sp == null) Text("No SpO₂ ${if (past) "that" else "last"} night — the ring's periodic SpO₂ may be off.", style = AppType.subhead, color = c.secondaryLabel)
                else {
                    val low = sp["low_count"].num()?.roundToInt() ?: 0
                    Text("Average ${sp["mean"].num()?.roundToInt()} % · lowest ${sp["min"].num()?.roundToInt()} %", style = AppType.headline)
                    Text(
                        if (low == 0) "No dips below 90 %" else "$low ${if (low == 1) "dip" else "dips"} below 90 %",
                        style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
                    )
                }
            }
        }
    }
}
