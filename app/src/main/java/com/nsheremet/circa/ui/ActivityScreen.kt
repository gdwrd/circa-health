package com.nsheremet.circa.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.nsheremet.circa.Prefs.stepGoal
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private const val SLOT_S = 300.0

internal class Hour(val h: Int, val steps: Double, val worn: Boolean)

@Composable
fun ActivityScreen(backend: Boolean?, date: LocalDate = LocalDate.now(), onDate: (LocalDate) -> Unit = {}, linkDate: String? = null, onLinkHandled: () -> Unit = {}) {
    if (backend != true) return NoBackendTab("Activity", backend)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // "Was this a workout?" notification: show the day it asks about.
    LaunchedEffect(linkDate) {
        if (linkDate != null) { runCatching { LocalDate.parse(linkDate) }.getOrNull()?.let(onDate); onLinkHandled() }
    }
    var hours by remember { mutableStateOf<List<JsonObject>?>(null) }
    var day by remember { mutableStateOf<JsonObject?>(null) }
    var hr by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var workouts by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var steps by remember { mutableStateOf<Trend?>(null) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<JsonObject?>(null) } // manual workout in the edit sheet
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
    suspend fun load() { trend(Api.trends(ctx, "steps", 30))?.let { steps = it } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(date) { open = null; hours = null; day = null; workouts = emptyList(); hr = emptyList(); loadDay() }
    // A day with a workout gets its journal entry once: POST /journal replaces the day's row (the backend's auto row would turn
    // manual), so read the journal window holding the day first.
    LaunchedEffect(workouts) {
        if (workouts.isEmpty()) return@LaunchedEffect
        val d = date.toString()
        val entries = Api.journal(ctx, ChronoUnit.DAYS.between(date, LocalDate.now()).toInt() + 1).obj()?.get("entries") as? JsonArray ?: return@LaunchedEffect
        if (entries.none { e -> e.obj()?.let { it["date"].str() == d && it["kind"].str() == "workout" } == true }) Api.addJournal(ctx, d, "workout")
    }

    val c = AppTheme.colors
    val today = date == LocalDate.now()
    LargeTitleScaffold("Activity", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load(); loadDay() } finally { refreshing = false } }
    }) {
        item { DayPicker(date, onDate) }
        val hs = hours ?: return@LargeTitleScaffold
        val total = hs.sumOf { it["steps"].num() ?: 0.0 }
        item {
            // Sleep's hero: label, big value, one line on the left, the day's score ring on the right; the hourly chart under it.
            InsetCard {
                if (total <= 0.0) return@InsetCard EmptyState(
                    Icons.Rounded.DirectionsWalk, if (today) "No steps yet today" else "No activity for this day",
                    "Steps are read every hour while you wear the ring.",
                )
                val goal = ctx.stepGoal
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.activity.copy(alpha = 0.12f), c.nightBottom))).padding(AppSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Steps", style = AppType.subhead, color = c.secondaryLabel)
                        Text(
                            buildAnnotatedString {
                                append("%,d".format(total.roundToInt()))
                                withStyle(SpanStyle(fontSize = 17.sp, color = c.secondaryLabel)) { append(" steps") }
                            },
                            style = AppType.largeTitle,
                        )
                        Text("${(total / goal * 100).roundToInt()}% of your ${"%,d".format(goal)} goal", style = AppType.subhead, color = c.secondaryLabel)
                    }
                    day?.get("activity").obj()?.get("score").num()?.let { s ->
                        ScoreRing(s.toFloat() / 100f, c.activity, Modifier.size(64.dp), stroke = 7.dp) {
                            Text(s.roundToInt().toString(), style = AppType.headline)
                        }
                    }
                }
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
                "Distance" to if (km > 0) "%.1f km".format(km) else "—",
                "Calories" to if (kcal > 0) "%,d kcal".format(kcal.roundToInt()) else "—",
            )
        }
        val cardio = day?.get("cardio").obj()
        if (total > 0.0 && cardio != null && (cardio["load"].num() != null || cardio["status"].str() != "ok")) item { CardioLoad(cardio, today) }
        item {
            Section("Workouts") {
                workouts.forEachIndexed { i, w ->
                    if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                    val start = w["start"].num()
                    val isOpen = start != null && start == open
                    WorkoutRow(w, isOpen) { open = if (isOpen) null else start }
                    if (isOpen) WorkoutHr(w, hr, if (w["manual"].str() == "true") ({ moving = w }) else null) { editing = w }
                }
                if (workouts.isNotEmpty()) Hairline(Modifier.padding(start = AppSpace.l))
                TintButton("Add workout", Modifier.padding(AppSpace.l)) { adding = true }
            }
        }
        item {
            Section("Steps · 30 days") {
                val t = steps
                if (t == null || t.value.isEmpty()) return@Section EmptyState(Icons.Rounded.DirectionsWalk, "Not enough data yet", "Keep the ring on; this fills in after a few syncs.")
                // TrendCard's header with the average over the days shown instead of the latest day (today's steps are in the hero).
                val avg = t.value.map { it.second }.average()
                Text("Average", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l))
                Value(avg, "steps", c.activity)
                t.base.lastOrNull()?.second?.takeIf { it.roundToInt() != avg.roundToInt() }?.let {
                    Text("Baseline ${"%,d".format(it.roundToInt())} steps", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l))
                }
                val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                val byX = t.value.toMap()
                val span = maxOf(t.value.last().first, 1.0)
                Chart(
                    listOf(t.value, t.base), c.activity, false, daySpacing(span), 0, x = 0.0..span, bars = true,
                    marker = { x -> "${byX[x]?.let { "%,d".format(it.roundToInt()) + " steps" } ?: "—"} · ${t.first.plusDays(x.toLong()).format(fmt)}" },
                ) { x -> t.first.plusDays(x.toLong()).format(fmt) }
            }
        }
    }
    editing?.let { w ->
        val cur = w["kind"].str()
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = c.card,
            title = { Text("What was this?") },
            text = {
                Column {
                    // "none" drops a detected session and deletes one entered by hand
                    (KINDS + ("none" to if (w["manual"].str() == "true") "Remove" else "Not a workout")).forEach { (k, name) ->
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
    if (adding) AddWorkout(date, { adding = false }) { on, start, end, kind ->
        val a = Api.addWorkout(ctx, on, start, end, kind)?.get("workouts") as? JsonArray
        // the reply lists the start day's workouts: not the shown day's when the start is the evening before
        if (a != null) { if (on == date) workouts = a.mapNotNull { it.obj() }; adding = false }
        a != null
    }
    moving?.let { w ->
        val old = w["start"].num()?.toLong() ?: return@let
        // the sheet's day is the end's: a manual workout is listed on its start day, which the sheet derives
        val endDay = Instant.ofEpochSecond(w["end"].num()?.toLong() ?: old).atZone(ZoneId.systemDefault()).toLocalDate()
        AddWorkout(endDay, { moving = null }, w) { on, start, end, kind ->
            val a = Api.moveWorkout(ctx, date, old, on, start, end, kind)?.get("workouts") as? JsonArray
            if (a != null) {
                if (on == date) { workouts = a.mapNotNull { it.obj() }; open = start.toDouble() } else loadDay() // it left the shown day
                moving = null
            }
            a != null
        }
    }
}

/**
 * Kind, start and end on [date] for a workout the ring missed; an end before the start means it began the evening
 * before, and [save] gets that day. [save] returns false to keep the dialog open. With [edit] (a manual workout,
 * [date] its end's day) the sheet starts from its kind and times.
 */
@Composable
private fun AddWorkout(date: LocalDate, onDismiss: () -> Unit, edit: JsonObject? = null, save: suspend (LocalDate, Long, Long, String) -> Boolean) {
    val c = AppTheme.colors
    val zone = ZoneId.systemDefault()
    val now = remember { LocalTime.now().withSecond(0).withNano(0) }
    fun at(k: String) = edit?.get(k).num()?.let { Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalTime() }
    var kind by remember { mutableStateOf(edit?.get("kind").str() ?: "ride") }
    var start by remember { mutableStateOf(at("start") ?: now.minusMinutes(30)) }
    var end by remember { mutableStateOf(at("end") ?: now) }
    var picking by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val on = if (end < start) date.minusDays(1) else date
    val s = on.atTime(start).atZone(zone).toEpochSecond()
    val e = date.atTime(end).atZone(zone).toEpochSecond()
    val future = e > Instant.now().epochSecond
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(if (edit != null) "Edit workout" else "Add a workout") },
        text = {
            Column {
                KINDS.forEach { (k, name) ->
                    Text(
                        name + if (k == kind) "  ✓" else "", style = AppType.body, color = if (k == kind) c.tint else c.label,
                        modifier = Modifier.fillMaxWidth().clickable { kind = k }.padding(vertical = AppSpace.m),
                    )
                }
                Hairline()
                listOf("Start" to start, "End" to end).forEach { (name, t) ->
                    Row(Modifier.fillMaxWidth().clickable { picking = name }.padding(vertical = AppSpace.m)) {
                        Text(name, style = AppType.body, modifier = Modifier.weight(1f))
                        Text(clock(t), style = AppType.body, color = c.tint)
                    }
                }
                Text(
                    if (future) "That time has not happened yet."
                    else if (failed) "Couldn't save. It must last 5 minutes to 12 hours, and the server must be reachable."
                    else if (end < start) "It starts the evening before and is listed on that day."
                    else "Heart rate and load show only what the ring measured in that time.",
                    style = AppType.footnote, color = c.secondaryLabel,
                )
                // spelled out with the day and both AM/PM markers: the 12-hour picker makes AM vs PM easy to miss
                Text(
                    "${dayLabel(on)} · ${clock(start)} – ${clock(end)}", style = AppType.subhead, color = c.label,
                    modifier = Modifier.padding(top = AppSpace.m),
                )
            }
        },
        confirmButton = {
            TextButton({ scope.launch { failed = !save(on, s, e, kind) } }, enabled = !future) {
                Text("Save", color = if (future) c.secondaryLabel else c.tint)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.tint) } },
    )
    picking?.let { which ->
        TimePickDialog((if (which == "Start") start else end).toString(), { picking = null }) { hhmm ->
            LocalTime.parse(hhmm).let { if (which == "Start") start = it else end = it }
            picking = null
        }
    }
}

/** Two equal tiles: label and value. */
@Composable
private fun Tiles(vararg tiles: Pair<String, String>) {
    val c = AppTheme.colors
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        tiles.forEach { (label, value) ->
            InsetCard(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(AppSpace.l)) {
                    Text(label, style = AppType.footnote, color = c.secondaryLabel)
                    Text(value, style = AppType.title)
                }
            }
        }
    }
}

private val ZONES = listOf("Easy", "Light", "Moderate", "Hard", "Max")
private val COACH = mapOf(
    "recovered" to "Recovered — room for a bigger day.",
    "steady" to "Steady — a normal day keeps you balanced.",
    "easy" to "Take it easy — recovery first.",
)

/** `/today.cardio`: load against the day's target, zone minutes; tap → five zone rows. */
@Composable
private fun CardioLoad(cd: JsonObject, today: Boolean) {
    val c = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val status = cd["status"].str()
    val load = cd["load"].num()
    val t = cd["target"].obj()
    val lo = t?.get("lo").num()
    val hi = t?.get("hi").num()
    val minutes = (cd["minutes"] as? JsonArray)?.map { it.num()?.roundToInt() ?: 0 }
    // A real 0 (UX4): heart rate never reached zone 1, so two zeros would read as a fault.
    val below = load?.roundToInt() == 0 && minutes != null && minutes.all { it == 0 }
    InsetCard {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = status != "needs_age" && minutes != null) { open = !open }.padding(AppSpace.l),
            verticalArrangement = Arrangement.spacedBy(AppSpace.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cardio load", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                if (status != "needs_age" && minutes != null) ExpandChevron(open)
            }
            if (status == "needs_age") {
                Text("Set your birth year in Settings to get heart-rate zones.", style = AppType.footnote, color = c.secondaryLabel)
                return@Column
            }
            if (below) Text("Below your zones ${if (today) "today" else "that day"}", style = AppType.headline)
            else Row(verticalAlignment = Alignment.Bottom) {
                if (load != null) CountUp(load.roundToInt(), AppType.title) else Text("—", style = AppType.title)
                if (lo != null && hi != null) Text(" of ${lo.roundToInt()}–${hi.roundToInt()}", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (status == "learning") Text("Learning your usual load · ${cd["days"].num()?.roundToInt() ?: 0}/5 days", style = AppType.subhead, color = c.secondaryLabel)
            if (lo != null && hi != null) {
                val now = load ?: 0.0
                val scale = maxOf(hi * 1.4, now * 1.1, 10.0)
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val r = CornerRadius(size.height / 2)
                    fun x(v: Double) = (size.width * v / scale).toFloat()
                    drawRoundRect(c.secondaryLabel.copy(alpha = 0.12f), cornerRadius = r)
                    drawRoundRect(c.activity.copy(alpha = 0.35f), Offset(x(lo), 0f), Size(x(hi) - x(lo), size.height), r)
                    if (now > 0) drawRoundRect(Brush.horizontalGradient(listOf(c.activity, c.light(c.activity)), endX = x(now)), size = Size(x(now), size.height), cornerRadius = r)
                    // Ticks keep the target visible where the fill covers the band.
                    listOf(lo, hi).filter { it < now }.forEach { drawLine(c.label, Offset(x(it), 0f), Offset(x(it), size.height), 2.dp.toPx()) }
                }
                COACH[t?.get("band").str()]?.let { Text(it + if (now > hi) " You're past ${if (today) "today's" else "that day's"} target." else "", style = AppType.subhead) }
            }
            if (minutes != null && !below) Text("${minutes.sum()} min in heart-rate zones", style = AppType.footnote, color = c.secondaryLabel)
        }
        AnimatedVisibility(open && minutes != null) {
            Column {
                minutes.orEmpty().forEachIndexed { i, m ->
                    Hairline(Modifier.padding(start = AppSpace.l))
                    Row(Modifier.fillMaxWidth().padding(horizontal = AppSpace.l, vertical = AppSpace.m), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.activity.copy(alpha = 0.35f + 0.1625f * i)))
                        Text("Zone ${i + 1} · ${ZONES[i]}", style = AppType.body, modifier = Modifier.padding(start = AppSpace.s).weight(1f))
                        Text(duration(m), style = AppType.body, color = c.secondaryLabel)
                    }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                Text(
                    "Zones are estimated from your age and resting heart rate. Each 5 minutes adds half its zone number to the load.",
                    style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m),
                )
            }
        }
    }
}

/** 24 hourly step columns (Stress's ThroughTheDay style); a ring-off hour is a short muted stub, not an empty gap. */
@Composable
internal fun HourlySteps(rows: List<Hour>, height: Dp = 160.dp) {
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
        producer, height,
    )
}

private val KINDS = listOf("walk" to "Walk", "ride" to "Ride", "workout" to "Workout")

/** "Walk · 42 min" / avg bpm, then "2:10–2:52 PM · max 141 · load 6", " · guessed" until the user confirms the kind; load 0 and "added by you" are in [WorkoutHr]. */
@Composable
private fun WorkoutRow(w: JsonObject, open: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val kind = w["kind"].str()
    val icon = when (kind) { "walk" -> Icons.Rounded.DirectionsWalk; "ride" -> Icons.Rounded.DirectionsBike; else -> Icons.Rounded.FitnessCenter }
    val a = clock(w["start"].num() ?: 0.0)
    val b = clock(w["end"].num() ?: 0.0)
    // "2:10–2:52 PM": drop the start's AM/PM when both share it.
    val span = (if (!a.last().isDigit() && a.takeLast(3) == b.takeLast(3)) a.dropLast(3) else a) + "–" + b
    // A workout entered by hand has bpm / load only where the ring measured something.
    val detail = listOfNotNull(
        span, w["max_bpm"].num()?.let { "max ${it.roundToInt()}" }, w["load"].num()?.roundToInt()?.takeIf { it > 0 }?.let { "load $it" },
        if (w["manual"].str() == "true") null else if (w["phone"].str() == "true") "noticed by your phone" else if (w["confirmed"].str() == "true") null else "guessed",
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = AppSpace.l, top = AppSpace.m, bottom = AppSpace.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.heart, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = AppSpace.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${KINDS.firstOrNull { it.first == kind }?.second ?: "Workout"} · ${duration(w["minutes"].num()?.roundToInt() ?: 0)}", style = AppType.body, modifier = Modifier.weight(1f))
                w["avg_bpm"].num()?.let { Text("avg ${it.roundToInt()} bpm", style = AppType.subhead, color = c.secondaryLabel) }
            }
            Text(detail, style = AppType.footnote, color = c.secondaryLabel)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel,
            modifier = Modifier.padding(end = AppSpace.s).rotate(if (open) 90f else 0f),
        )
    }
}

/** Open workout row: the day's heart rate inside [start, end] and the relabel button. */
@Composable
private fun WorkoutHr(w: JsonObject, hr: List<Pair<Double, Double>>, onEdit: (() -> Unit)?, onRelabel: () -> Unit) {
    val c = AppTheme.colors
    val s = w["start"].num() ?: return
    val e = w["end"].num() ?: return
    val xy = hr.filter { it.first in s..e && it.second > 0 }.associate { floor((it.first - s) / SLOT_S) to it.second }.toList() // snapped: see HeartScreen
    val name = (KINDS.firstOrNull { it.first == w["kind"].str() }?.second ?: "Workout").lowercase()
    Column(Modifier.padding(bottom = AppSpace.l)) {
        Text(
            w["avg_bpm"].num()?.let { "Heart rate during this $name: avg ${it.roundToInt()} · max ${w["max_bpm"].num()?.roundToInt()} bpm" }
                ?: "The ring recorded no heart rate during this $name.",
            style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.l),
        )
        // what the row leaves out: any load (0 too) and who entered it
        val more = listOfNotNull(w["load"].num()?.let { "Load ${it.roundToInt()}" }, if (w["manual"].str() == "true") "Added by you" else null)
        if (more.isNotEmpty()) Text(more.joinToString(" · "), style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.l))
        if (xy.isNotEmpty()) {
            val byX = xy.toMap()
            val slots = (e - s) / SLOT_S
            Chart(
                listOf(xy), c.heart, true, maxOf(1, (slots / 3).toInt()), 0, x = 0.0..slots, height = 120.dp,
                marker = { x -> "${byX[x]?.roundToInt()} bpm · ${clock(s + x * SLOT_S)}" },
            ) { x -> clock(s + x * SLOT_S) }
        }
        Row(Modifier.padding(horizontal = AppSpace.l), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            TintButton("What was this?", Modifier.weight(1f)) { onRelabel() }
            if (onEdit != null) TintButton("Edit", Modifier.weight(1f)) { onEdit() }
        }
    }
}
