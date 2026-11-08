package dev.ring.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private class Seg(val startS: Double, val endS: Double, val stage: Int)

private fun hm(min: Double) = "${(min / 60).toInt()} h ${(min % 60).roundToInt()} min"
private fun clock(sec: Double) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date((sec * 1000).toLong()))

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
    if (backend != true) return PlaceholderTab("Sleep", backend)
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var trend by remember { mutableStateOf<List<Pair<String, Double?>>?>(null) }
    val producer = remember { CartesianChartModelProducer() }
    val need = remember { ctx.sleepNeedHours }
    LaunchedEffect(Unit) {
        data = Api.sleep(ctx, LocalDate.now().toString()).obj()
        val pts = (Api.trends(ctx, "sleep_min", 7).obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj() }
        trend = pts?.map { (it["date"] as? JsonPrimitive)?.contentOrNull.orEmpty() to it["value"].num() }
        trend?.let { t -> producer.runTransaction { columnSeries { series(t.map { (it.second ?: 0.0) / 60 }) } } }
    }

    val c = AppTheme.colors
    LargeTitleScaffold("Sleep") {
        val d = data ?: return@LargeTitleScaffold
        val ses = d["session"].obj()
        if (ses == null) item {
            InsetCard {
                Text("No sleep recorded for last night", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l))
            }
        } else {
            val startS = ses["start"].num() ?: 0.0
            val endS = ses["end"].num() ?: startS
            val total = ses["total_min"].num() ?: 0.0
            val awake = ses["awake_min"].num() ?: 0.0
            val rem = ses["rem_min"].num() ?: 0.0
            val deep = ses["deep_min"].num() ?: 0.0
            val score = d["score"].num()
            item {
                InsetCard {
                    Row(Modifier.padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
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
                        }
                        ScoreRing((score ?: 0.0).toFloat() / 100f, c.sleep, Modifier.size(64.dp), stroke = 7.dp) {
                            Text(score?.roundToInt()?.toString() ?: "—", style = AppType.headline)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    Text("Stages", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                    InsetCard { Hypnogram(segments(d["stages"] as? JsonArray, endS), startS, endS) }
                }
            }
            item {
                val light = (total - rem - deep).coerceAtLeast(0.0)
                InsetCard {
                    listOf(
                        "Awake" to (awake to c.stageAwake), "REM" to (rem to c.stageRem),
                        "Light" to (light to c.stageCore), "Deep" to (deep to c.stageDeep),
                    ).forEachIndexed { i, (name, v) ->
                        if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                        Row(Modifier.fillMaxWidth().padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(v.second, CircleShape))
                            Text(name, style = AppType.body, modifier = Modifier.weight(1f).padding(start = AppSpace.m))
                            Text(hm(v.first), style = AppType.body, color = c.secondaryLabel)
                        }
                    }
                }
            }
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
                    CartesianChartHost(
                        rememberCartesianChart(
                            rememberColumnCartesianLayer(
                                ColumnCartesianLayer.ColumnProvider.series(
                                    rememberLineComponent(fill = fill(c.sleep), thickness = 18.dp, shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30)),
                                ),
                                rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxOf(9.0, need + 1.5)),
                            ),
                            startAxis = VerticalAxis.rememberStart(
                                guideline = rememberLineComponent(fill = fill(c.separator), thickness = 0.5.dp),
                                itemPlacer = VerticalAxis.ItemPlacer.step({ 3.0 }),
                            ),
                            bottomAxis = HorizontalAxis.rememberBottom(
                                guideline = null,
                                valueFormatter = CartesianValueFormatter { _, x, _ ->
                                    runCatching { LocalDate.parse(t[x.toInt()].first).dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()) }.getOrDefault("")
                                },
                            ),
                            decorations = listOf(
                                HorizontalLine(
                                    y = { need.toDouble() },
                                    line = rememberLineComponent(fill = fill(c.secondaryLabel), thickness = 1.dp),
                                ),
                            ),
                        ),
                        producer,
                        Modifier.fillMaxWidth().height(200.dp).padding(AppSpace.l),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hypnogram(segs: List<Seg>, startS: Double, endS: Double) {
    val c = AppTheme.colors
    val measurer = rememberTextMeasurer()
    val label = AppType.footnote.copy(color = c.secondaryLabel)
    val lanes = listOf("Awake" to 4, "REM" to 3, "Light" to 1, "Deep" to 2)
    val colors = mapOf(4 to c.stageAwake, 3 to c.stageRem, 1 to c.stageCore, 2 to c.stageDeep)
    val line = c.separator
    Canvas(Modifier.fillMaxWidth().height(184.dp).padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
        val left = 48.dp.toPx()
        val axisH = 20.dp.toPx()
        val laneH = (size.height - axisH) / 4
        val barH = 14.dp.toPx()
        val span = (endS - startS).coerceAtLeast(1.0)
        fun x(s: Double) = left + ((s - startS) / span * (size.width - left)).toFloat()
        lanes.forEachIndexed { i, (name, _) ->
            val cy = laneH * i + laneH / 2
            drawLine(line, Offset(left, cy), Offset(size.width, cy), 0.5.dp.toPx())
            val m = measurer.measure(name, label)
            drawText(m, topLeft = Offset(0f, cy - m.size.height / 2f))
        }
        segs.forEach { s ->
            val i = lanes.indexOfFirst { it.second == s.stage }
            val cy = laneH * i + laneH / 2
            val x0 = x(s.startS)
            val w = (x(s.endS) - x0).coerceAtLeast(3.dp.toPx())
            drawRoundRect(colors.getValue(s.stage), Offset(x0, cy - barH / 2), Size(w, barH), CornerRadius(barH / 2))
        }
        val step = if (span > 6 * 3600) 2 else 1
        val fmt = SimpleDateFormat("h a", Locale.getDefault())
        var t = ceil(startS / 3600).toLong() * 3600
        var n = 0
        while (t <= endS) {
            if (n++ % step == 0) {
                val m = measurer.measure(fmt.format(Date(t * 1000)), label)
                val tx = (x(t.toDouble()) - m.size.width / 2f).coerceIn(left, size.width - m.size.width)
                drawText(m, topLeft = Offset(tx, size.height - m.size.height))
            }
            t += 3600
        }
    }
}
