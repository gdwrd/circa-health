package dev.ring.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private fun hm(min: Int) = "${min / 60} h ${min % 60} min"

private val LEVELS = listOf(
    "limited" to "Your body has had little room to recover lately.",
    "adequate" to "Recovery and daytime load are roughly in balance.",
    "solid" to "You are recovering well from your daily load.",
    "strong" to "Recovery is keeping comfortably ahead of your daily load.",
    "exceptional" to "Recovery is consistently well ahead of your daily load.",
)

@Composable
fun StressScreen(backend: Boolean?) {
    if (backend != true) return NoBackendTab("Stress", backend)
    val ctx = LocalContext.current
    var detail by remember { mutableStateOf(false) }
    if (detail) return ResilienceDetail { detail = false }
    var data by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() { data = Api.today(ctx).obj() }
    LaunchedEffect(Unit) { load() }

    val c = AppTheme.colors
    LargeTitleScaffold("Stress", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        val d = data ?: return@LargeTitleScaffold
        val st = d["stress"].obj()
        val zones = listOf(
            Triple("restored", "Restored", c.zoneRestored), Triple("relaxed", "Relaxed", c.zoneRelaxed),
            Triple("engaged", "Engaged", c.zoneEngaged), Triple("stressed", "Stressed", c.zoneStressed),
        ).map { (key, name, col) -> Triple(name, (st?.get(key).num() ?: 0.0).roundToInt() * 30, col) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Today", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    if (zones.all { it.second == 0 }) {
                        EmptyState(Icons.Rounded.Spa, "No stress data yet today", "Stress is read every 30 minutes while you wear the ring.")
                    } else {
                        Row(Modifier.fillMaxWidth().padding(AppSpace.l), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            zones.filter { it.second > 0 }.forEach { (_, min, col) ->
                                Box(Modifier.weight(min.toFloat()).height(16.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(col.copy(alpha = 0.75f), col))))
                            }
                        }
                        zones.forEach { (name, min, col) ->
                            Hairline(Modifier.padding(start = AppSpace.l))
                            Row(Modifier.fillMaxWidth().padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(col, CircleShape))
                                Text(name, style = AppType.body, modifier = Modifier.weight(1f).padding(start = AppSpace.m))
                                Text(hm(min), style = AppType.body, color = c.secondaryLabel)
                            }
                        }
                    }
                }
            }
        }
        if (st != null) item {
            val min = ((st["stressed_hours"].num() ?: 0.0) * 60).roundToInt()
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Daytime stress load", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    Column(Modifier.padding(AppSpace.l)) {
                        Text(
                            buildAnnotatedString {
                                val unit = SpanStyle(fontSize = 17.sp, color = c.secondaryLabel)
                                append((min / 60).toString()); withStyle(unit) { append(" h ") }
                                append((min % 60).toString()); withStyle(unit) { append(" min") }
                            },
                            style = AppType.largeTitle,
                        )
                        Text("Time in the stressed zone while awake", style = AppType.subhead, color = c.secondaryLabel)
                    }
                }
            }
        }
        item {
            val level = (d["resilience"] as? JsonPrimitive)?.contentOrNull
            val idx = LEVELS.indexOfFirst { it.first == level }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Resilience", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    Row(Modifier.clickable { detail = true }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        if (idx < 0) {
                            Text(
                                "Learning · ${(d["baseline_days"] as? JsonPrimitive)?.contentOrNull ?: "?"} days",
                                style = AppType.headline,
                            )
                            Text("Resilience appears once 5 of the last 14 days have data.", style = AppType.subhead, color = c.secondaryLabel)
                        } else {
                            Text(LEVELS[idx].first.replaceFirstChar { it.uppercase() }, style = AppType.largeTitle)
                            Capsules(idx)
                            Text(LEVELS[idx].second, style = AppType.subhead, color = c.secondaryLabel)
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                        tint = c.secondaryLabel, modifier = Modifier.padding(end = AppSpace.m),
                    )
                    }
                }
            }
        }
    }
}

/** Five capsules; the first idx+1 fill one after another (600 ms total), at once under reduce-motion. */
@Composable
private fun Capsules(idx: Int) {
    val c = AppTheme.colors
    val reduce = LocalReduceMotion.current
    val fill = remember { Animatable(if (reduce) idx + 1f else 0f) }
    LaunchedEffect(idx) { if (reduce) fill.snapTo(idx + 1f) else fill.animateTo(idx + 1f, tween(600, easing = EaseOut)) }
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
        repeat(5) { i ->
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(c.readiness.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth((fill.value - i).coerceIn(0f, 1f)).height(6.dp).background(c.readiness))
            }
        }
    }
}

private fun points(json: JsonElement?): List<Pair<LocalDate, Double?>> =
    (json.obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj() }
        ?.map { LocalDate.parse((it["date"] as JsonPrimitive).content) to it["value"].num() }.orEmpty()

@Composable
private fun ResilienceDetail(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var load by remember { mutableStateOf<List<Pair<LocalDate, Double?>>?>(null) }
    var ready by remember { mutableStateOf<List<Pair<LocalDate, Double?>>>(emptyList()) }
    LaunchedEffect(Unit) {
        ready = points(Api.trends(ctx, "readiness", 14))
        load = points(Api.trends(ctx, "stress_load", 14))
    }
    val c = AppTheme.colors
    LargeTitleScaffold(
        "Resilience",
        back = { Text("‹ Stress", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs)) },
    ) {
        val l = load ?: return@LargeTitleScaffold
        val lv = l.mapNotNull { it.second }
        val rv = ready.mapNotNull { it.second }
        if (lv.size < 5 || rv.size < 5) {
            item {
                InsetCard {
                    Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        Text("Learning · ${minOf(lv.size, rv.size)}/5 days", style = AppType.headline)
                        Text("Resilience needs 5 days of stress load and readiness in the last 14.", style = AppType.subhead, color = c.secondaryLabel)
                    }
                }
            }
            return@LargeTitleScaffold
        }
        val la = lv.average()
        val ra = rv.average()
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Contributors", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    Bar("Daytime stress (14-day avg)", String.format(Locale.US, "%.1f h", la), la / 8, c.zoneStressed)
                    Hairline(Modifier.padding(start = AppSpace.l))
                    Bar("Readiness (14-day avg)", ra.roundToInt().toString(), ra / 100, c.readiness)
                }
                Text(
                    "Resilience compares how much of your day is spent in the stressed zone with how well you recover. Lower stress and higher readiness raise it.",
                    style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.xs),
                )
            }
        }
        item { MiniChart("Daytime stress load · hours", l, c.zoneStressed, true) }
        item { MiniChart("Readiness", ready, c.readiness, false) }
    }
}

@Composable
private fun Bar(name: String, value: String, frac: Double, color: Color) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
        Row {
            Text(name, style = AppType.body, modifier = Modifier.weight(1f))
            Text(value, style = AppType.body, color = c.secondaryLabel)
        }
        Box(Modifier.padding(top = AppSpace.s).fillMaxWidth().height(4.dp).clip(CircleShape).background(color.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.0, 1.0).toFloat()).height(4.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun MiniChart(title: String, pts: List<Pair<LocalDate, Double?>>, color: Color, column: Boolean) {
    val c = AppTheme.colors
    val first = pts.firstOrNull()?.first ?: return
    val xy = pts.mapNotNull { (d, v) -> v?.let { (d.toEpochDay() - first.toEpochDay()).toDouble() to it } }
    if (xy.isEmpty()) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(pts) {
        producer.runTransaction {
            if (column) columnSeries { series(xy.map { it.first }, xy.map { it.second }) }
            else lineSeries { series(xy.map { it.first }, xy.map { it.second }) }
        }
    }
    val top = if (column) maxOf(2.0, ceil(xy.maxOf { it.second })) else 100.0
    val range = CartesianLayerRangeProvider.fixed(minX = -0.5, maxX = pts.size - 0.5, minY = 0.0, maxY = top)
    val layer = if (column) {
        rememberColumnCartesianLayer(
            ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(fill = fill(color), thickness = 12.dp, shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30)),
            ),
            rangeProvider = range,
        )
    } else {
        rememberLineCartesianLayer(
            LineCartesianLayer.LineProvider.series(
                chartLine(color),
            ),
            rangeProvider = range,
        )
    }
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text(title, style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            val byX = xy.toMap()
            ScrubChartHost(
                rememberCartesianChart(
                    layer,
                    startAxis = startAxis(top / 2),
                    bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> first.plusDays(x.toLong()).format(fmt) }, 4),
                    marker = rememberScrubMarker(color) { x ->
                        "${byX[x]?.let { if (column) "%.1f h".format(it) else it.roundToInt().toString() } ?: "—"} · ${first.plusDays(x.toLong()).format(fmt)}"
                    },
                ),
                producer, 140.dp,
            )
        }
    }
}
