package dev.ring.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import dev.ring.health.Prefs.stepGoal
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private const val SLOT_S = 300.0

private class Hour(val h: Int, val steps: Double, val worn: Boolean)

@Composable
fun ActivityScreen(backend: Boolean?) {
    if (backend != true) return NoBackendTab("Activity", backend)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now()) }
    var hours by remember { mutableStateOf<List<JsonObject>?>(null) }
    var day by remember { mutableStateOf<JsonObject?>(null) }
    var hr by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var workouts by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var steps by remember { mutableStateOf<Trend?>(null) }
    var journalWorkout by remember { mutableStateOf<Set<String>>(emptySet()) } // dates with a journal "workout"
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var open by remember { mutableStateOf<Double?>(null) } // start of the expanded workout row
    var refreshing by remember { mutableStateOf(false) }
    suspend fun loadDay() {
        Api.activity(ctx, date).obj()?.let { a -> hours = (a["hours"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty() }
        Api.today(ctx, date).obj()?.let { d ->
            day = d
            workouts = (d["workouts"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
        }
        Api.hr(ctx, date).obj()?.let { h ->
            hr = (h["points"] as? JsonArray)
                ?.mapNotNull { o -> o.obj()?.let { p -> p["t"].num()?.let { t -> p["bpm"].num()?.let { t to it } } } }
                ?.sortedBy { it.first }.orEmpty()
        }
    }
    suspend fun load() {
        trend(Api.trends(ctx, "steps", 30))?.let { steps = it }
        Api.journal(ctx, 30).obj()?.let { j ->
            journalWorkout = (j["entries"] as? JsonArray)?.mapNotNull { it.obj() }
                ?.filter { it["kind"].str() == "workout" }?.mapNotNull { it["date"].str() }.orEmpty().toSet()
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(date) { open = null; hours = null; day = null; workouts = emptyList(); hr = emptyList(); loadDay() }

    val c = AppTheme.colors
    val today = date == LocalDate.now()
    LargeTitleScaffold("Activity", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load(); loadDay() } finally { refreshing = false } }
    }) {
        item { DayPicker(date) { date = it } }
        val hs = hours ?: return@LargeTitleScaffold
        val total = hs.sumOf { it["steps"].num() ?: 0.0 }
        item {
            Section("Steps") {
                if (total <= 0.0) return@Section EmptyState(
                    Icons.Rounded.DirectionsWalk, if (today) "No steps yet today" else "No activity for this day",
                    "Steps are read every hour while you wear the ring.",
                )
                val goal = ctx.stepGoal
                Box(Modifier.padding(top = AppSpace.l)) { Value(total, "steps", c.activity) }
                Text(
                    "${dayLabel(date)} · ${(total / goal * 100).roundToInt()}% of your $goal goal", style = AppType.subhead, color = c.secondaryLabel,
                    modifier = Modifier.padding(start = AppSpace.l),
                )
                val rows = hs.mapNotNull { o -> o["h"].num()?.let { Hour(it.toInt(), o["steps"].num() ?: 0.0, o["worn"].str() == "true") } }
                HourlySteps(rows)
                // Worn awake hours (08–22, the notify rule) under 50 steps; today's unfinished hour does not count yet.
                val now = LocalTime.now().hour
                val inactive = rows.count { it.worn && it.h in 8..21 && it.steps < 50 && !(today && it.h >= now) }
                if (inactive > 0) Text(
                    "$inactive inactive ${if (inactive == 1) "hour" else "hours"}", style = AppType.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(start = AppSpace.l, bottom = AppSpace.l),
                )
            }
        }
        val kcal = hs.sumOf { it["calories"].num() ?: 0.0 }
        val km = hs.sumOf { it["distance_m"].num() ?: 0.0 } / 1000
        if (total > 0.0) item {
            Tiles(
                Triple("Calories", if (kcal > 0) "${kcal.roundToInt()} kcal" else "—", "Reported by the ring for this day."),
                Triple("Distance", if (km > 0) "%.1f km".format(km) else "—", "From your steps, as the ring counts them."),
            )
        }
        val cardio = day?.get("cardio").obj()
        val status = cardio?.get("status").str()
        val load = cardio?.get("load").num()
        if (total > 0.0 && (load != null || status != "ok")) item {
            val t = cardio?.get("target").obj()
            val lo = t?.get("lo").num()?.roundToInt()
            val hi = t?.get("hi").num()?.roundToInt()
            val mins = (cardio?.get("minutes") as? JsonArray)?.sumOf { it.num() ?: 0.0 }?.roundToInt() ?: 0
            Tiles(
                Triple(
                    "Cardio load", load?.roundToInt()?.toString() ?: "—",
                    when {
                        lo != null && hi != null -> "of $lo–$hi ${if (today) "today" else "that day"}"
                        status == "learning" -> "Learning · ${cardio?.get("days").num()?.roundToInt() ?: 0}/5 days"
                        status == "needs_age" -> "Set your birth year in Settings"
                        else -> "From time in heart-rate zones."
                    },
                ),
                Triple("In zones", duration(mins), "Time with your heart rate in a zone."),
            )
        }
        if (workouts.isNotEmpty()) item {
            Section("Workouts") {
                workouts.forEachIndexed { i, w ->
                    if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                    val start = w["start"].num()
                    val isOpen = start != null && start == open
                    WorkoutRow(w, isOpen) { open = if (isOpen) null else start }
                    if (isOpen) WorkoutHr(w, hr) { editing = w }
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
        stepsTrend(steps)
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

private fun androidx.compose.foundation.lazy.LazyListScope.stepsTrend(t: Trend?) =
    item { TrendCard("Steps · 30 days", t, "steps", AppTheme.colors.activity) }

/** Two equal tiles: label, value, one footnote sentence (Heart's Resting HR / HRV row). */
@Composable
private fun Tiles(vararg tiles: Triple<String, String, String>) {
    val c = AppTheme.colors
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        tiles.forEach { (label, value, foot) ->
            InsetCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(AppSpace.l)) {
                    Text(label, style = AppType.footnote, color = c.secondaryLabel)
                    Text(value, style = AppType.title)
                    Text(foot, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs))
                }
            }
        }
    }
}

/** 24 hourly step columns (Stress's ThroughTheDay style); a ring-off hour is a short muted stub, not an empty gap. */
@Composable
private fun HourlySteps(rows: List<Hour>) {
    val c = AppTheme.colors
    val peak = rows.maxOfOrNull { it.steps } ?: 0.0
    val step = if (peak > 2000) 1000.0 else if (peak > 1000) 500.0 else 250.0
    val top = ceil(maxOf(500.0, peak) / step) * step
    val byX = rows.associateBy { it.h.toDouble() }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(rows) {
        producer.runTransaction {
            columnSeries { series(rows.map { it.h }, rows.map { if (it.worn) it.steps else maxOf(it.steps, top * 0.04) }) }
        }
    }
    val shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30)
    val worn = rememberLineComponent(fill = fill(c.activity), thickness = 8.dp, shape = shape)
    val off = rememberLineComponent(fill = fill(c.secondaryLabel.copy(alpha = 0.35f)), thickness = 8.dp, shape = shape)
    val layer = rememberColumnCartesianLayer(
        object : ColumnCartesianLayer.ColumnProvider {
            override fun getColumn(entry: ColumnCartesianLayerModel.Entry, seriesIndex: Int, extraStore: ExtraStore) =
                if (byX[entry.x]?.worn != false) worn else off
            override fun getWidestSeriesColumn(seriesIndex: Int, extraStore: ExtraStore) = worn
        },
        columnCollectionSpacing = 2.dp,
        rangeProvider = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = 23.0, minY = 0.0, maxY = top),
    )
    fun hour(x: Double) = clock(LocalTime.of(x.toInt(), 0)).replace(":00 ", " ")
    ScrubChartHost(
        rememberCartesianChart(
            layer,
            startAxis = startAxis(step),
            bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> hour(x) }, 6),
            marker = rememberScrubMarker(c.activity) { x ->
                byX[x]?.let { "${it.steps.roundToInt()} steps · ${hour(x)}" + if (it.worn) "" else " · not worn" } ?: "—"
            },
        ),
        producer, 160.dp,
    )
}

private val KINDS = listOf("walk" to "Walk", "ride" to "Ride", "workout" to "Workout")

/** "Walk · 42 min" / avg bpm, then "2:10–2:52 PM · max 141 · load 6", " · guessed" until the user confirms the kind. */
@Composable
private fun WorkoutRow(w: JsonObject, open: Boolean, onClick: () -> Unit) {
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
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel,
            modifier = Modifier.padding(end = AppSpace.s).rotate(if (open) 90f else 0f),
        )
    }
}

/** Open workout row: the day's heart rate inside [start, end] and the relabel button. */
@Composable
private fun WorkoutHr(w: JsonObject, hr: List<Pair<Double, Double>>, onRelabel: () -> Unit) {
    val c = AppTheme.colors
    val s = w["start"].num() ?: return
    val e = w["end"].num() ?: return
    val xy = hr.filter { it.first in s..e && it.second > 0 }.associate { floor((it.first - s) / SLOT_S) to it.second }.toList() // snapped: see HeartScreen
    val name = (KINDS.firstOrNull { it.first == w["kind"].str() }?.second ?: "Workout").lowercase()
    Column(Modifier.padding(bottom = AppSpace.l)) {
        Text(
            "Heart rate during this $name: avg ${w["avg_bpm"].num()?.roundToInt()} · max ${w["max_bpm"].num()?.roundToInt()} bpm",
            style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.l),
        )
        if (xy.isNotEmpty()) {
            val byX = xy.toMap()
            val slots = (e - s) / SLOT_S
            Chart(
                listOf(xy), c.heart, true, maxOf(1, (slots / 3).toInt()), 0, x = 0.0..slots, height = 120.dp,
                marker = { x -> "${byX[x]?.roundToInt()} bpm · ${clock(s + x * SLOT_S)}" },
            ) { x -> clock(s + x * SLOT_S) }
        }
        TintButton("What was this?", Modifier.padding(horizontal = AppSpace.l)) { onRelabel() }
    }
}
