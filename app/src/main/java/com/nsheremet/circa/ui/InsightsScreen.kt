package com.nsheremet.circa.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

/** Trends › Insights: the experiment, What affects you and what comes before each thing you mark. */
@Composable
fun InsightsScreen(header: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var insights by remember { mutableStateOf<JsonObject?>(null) }
    var exp by remember { mutableStateOf<JsonObject?>(null) } // `/experiments.active`
    var felt by remember { mutableStateOf(false) } // Guess first is on (from /today)
    var testing by remember { mutableStateOf(false) }
    var things by remember { mutableStateOf<List<JsonObject>>(emptyList()) } // `/marks.things` that have marks
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() {
        Api.today(ctx).obj()?.let { felt = it["guess"].obj()?.get("on").str() == "true" }
        Api.insights(ctx).obj()?.let { insights = it["kinds"].obj() }
        Api.experiments(ctx).obj()?.let { exp = it["active"].obj() }
        (Api.marks(ctx).obj()?.get("things") as? JsonArray)?.let { a -> things = a.mapNotNull { it.obj() }.filter { (it["n"].num() ?: 0.0) > 0 } }
    }
    LaunchedEffect(Unit) { load() }
    LargeTitleScaffold("Trends", refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        item { Box(Modifier.padding(bottom = AppSpace.m)) { header() } }
        exp?.let { x ->
            item {
                ExperimentCard(
                    x,
                    onAgain = { scope.launch { Api.startExperiment(ctx, x["kind"].str().orEmpty(), x["metric"].str().orEmpty())?.let { exp = it["active"].obj() } } },
                    onDone = { scope.launch { Api.stopExperiment(ctx, x["id"].num()?.toInt() ?: return@launch)?.let { exp = it["active"].obj() } } },
                )
            }
        }
        // Kinds the user has logged at least once; never-logged kinds would all read "log 3 more days".
        val kinds = insights?.let { k -> AFFECT_KINDS.mapNotNull { kind -> k[kind].obj()?.takeIf { it["status"].str() == "ok" || (it["n_with"].num() ?: 0.0) > 0 }?.let { kind to it } } }
        // One experiment at a time: the entry row hides while one is on the card.
        if (!kinds.isNullOrEmpty()) item { WhatAffectsYou(kinds, if (exp == null) ({ testing = true }) else null) }
        if (things.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Things you mark", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.m)) { things.forEach { MarkInsight(it) } }
            }
        }
    }
    if (testing) TestDialog(felt, { testing = false }) { kind, metric ->
        testing = false
        scope.launch { Api.startExperiment(ctx, kind, metric)?.let { exp = it["active"].obj() } }
    }
}

private val AFFECT_KINDS = listOf("alcohol", "caffeine_late", "late_meal", "workout", "stress", "travel", "sick", "breathe")

private val AFTER = mapOf(
    "alcohol" to "after alcohol", "caffeine_late" to "after late caffeine", "late_meal" to "after a late meal",
    "workout" to "after a workout", "stress" to "after a stressful day", "travel" to "after travel",
    "breathe" to "after breathing", "phone_in_bed" to "after using your phone in bed",
)

// 1 = higher is better, -1 = lower is better (wind_down: later is worse); bedtime is neither.
private val BETTER = mapOf("sleep_score" to 1, "deep_min" to 1, "rem_min" to 1, "hrv" to 1, "total_min" to 1, "rhr" to -1, "awake_min" to -1, "strain" to -1, "day_hr" to -1, "stress" to -1, "wind_down" to -1, "felt" to 1, "stress_load" to -1, "steps" to 1)

/** Pill / change colour: good, bad or neutral for this metric. */
@Composable
internal fun effectColor(metric: String, delta: Double): Color {
    val c = AppTheme.colors
    val dir = if (kotlin.math.abs(delta) < (if (metric == "strain" || metric == "stress_load") 0.05 else 0.5)) 0.0 else (BETTER[metric] ?: 0) * delta
    return if (dir > 0) c.goodText else if (dir < 0) c.badText else c.secondaryLabel
}

private fun sentence(kind: String, e: JsonObject): String? {
    val m = e["metric"].str() ?: return null
    val w = e["with"].num() ?: return null
    val wo = e["without"].num() ?: return null
    if (kind == "sick") return "Around days you logged feeling unwell, your strain index averaged ${metricValue(m, w)} vs ${metricValue(m, wo)} on other nights."
    val on = "On nights ${AFTER[kind]}"
    return if (m == "bed_start") "$on you tended to fall asleep at ${bedClock(w)} vs ${bedClock(wo)}."
    else if (m == "wind_down") "$on your body started to settle at ${bedClock(w)} vs ${bedClock(wo)}."
    else if (m == "felt") "The morning ${AFTER[kind]} you felt ${metricValue(m, w)} vs ${metricValue(m, wo)} out of 100."
    else "$on your ${METRIC[m]?.second ?: m} averaged ${metricValue(m, w)} vs ${metricValue(m, wo)}."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatAffectsYou(kinds: List<Pair<String, JsonObject>>, onTest: (() -> Unit)?) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    fun label(kind: String) = if (kind == "sick") "Feeling unwell" else KIND_LABEL[kind] ?: kind
    // Rows that would only say "log n more days" (no stress, lead or cost line) become one line at the end.
    val (rows, waiting) = kinds.partition { (_, k) ->
        k["status"].str() == "ok" || (k["n_with"].num()?.roundToInt() ?: 0) >= 3 || k["stress"].obj()?.get("delta").num() != null ||
            (k["lead"].obj()?.get("days").num()?.roundToInt() ?: 0) > 0 || k["cost"].obj()?.let { it["status"].str() == "ok" && it["nights"].num() != null } == true
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("What affects you", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        if (rows.isNotEmpty() || onTest != null) InsetCard {
            rows.forEachIndexed { i, (kind, k) ->
                if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                val ok = k["status"].str() == "ok"
                var open by remember { mutableStateOf(false) }
                val nWith = k["n_with"].num()?.roundToInt() ?: 0
                val name = label(kind)
                // Breathe only: the ring's stress in the hour after vs before a session.
                val stress = k["stress"].obj()?.let { s -> s["delta"].num()?.let { d -> @Composable {
                    val r = d.roundToInt()
                    Text(
                        "Stress ${if (r < 0) "−" else "+"}${kotlin.math.abs(r)} in the hour after · ${s["n"].num()?.roundToInt() ?: 0} sessions",
                        style = AppType.subhead, color = effectColor("stress", d),
                    )
                } } }
                // Sick only: days the strain index was already raised before the logged day (median over episodes).
                val lead = k["lead"].obj()?.get("days").num()?.roundToInt()?.takeIf { it > 0 }?.coerceAtMost(7)?.let { n -> @Composable {
                    LeadStrip(n)
                    Text("Your body signals rose about $n ${if (n == 1) "day" else "days"} before you felt sick.", style = AppType.subhead)
                } }
                // Kinds with 3 finished episodes of their own: the median raised nights after one.
                val cost = k["cost"].obj()?.takeIf { it["status"].str() == "ok" }?.let { p -> p["nights"].num()?.let { v -> @Composable {
                    Text(
                        (if (v == 0.0) "Usually no extra nights" else "Usually costs you ${nightsText(v)}") + " · ${p["n"].num()?.roundToInt() ?: 0} times",
                        style = AppType.subhead, color = c.secondaryLabel,
                    )
                } } }
                Column(Modifier.fillMaxWidth().clickable(enabled = ok) { haptic.tick(); open = !open }.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    if (!ok) {
                        val need = 3 - nWith
                        val wo = 3 - (k["n_without"].num()?.roundToInt() ?: 0)
                        Text(
                            "$name · " + if (need > 0) "log $need more ${if (need == 1) "day" else "days"} to see the effect." else "needs $wo more ${if (wo == 1) "day" else "days"} without it.",
                            style = AppType.subhead, color = c.secondaryLabel,
                        )
                        stress?.invoke()
                        lead?.invoke()
                        cost?.invoke()
                        return@Column
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = AppType.headline)
                        Text(" · $nWith ${if (kind == "sick") "days" else "nights"}", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                        ExpandChevron(open)
                    }
                    stress?.invoke()
                    lead?.invoke()
                    val top = (k["top"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
                    if (top.isEmpty()) Text("No clear effect so far.", style = AppType.subhead, color = c.secondaryLabel)
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        top.forEach { e ->
                            val m = e["metric"].str().orEmpty()
                            val d = e["delta"].num() ?: 0.0
                            val col = effectColor(m, d)
                            Text(
                                "${METRIC[m]?.first ?: m} ${metricDelta(m, d, e["pct"].num())}",
                                style = AppType.footnote.copy(fontWeight = FontWeight.SemiBold), color = col,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    cost?.invoke()
                    AnimatedVisibility(open) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                            (k["all"] as? JsonArray)?.mapNotNull { e -> e.obj()?.let { sentence(kind, it) } }?.forEach { Text(it, style = AppType.subhead) }
                            Text("Averages from your own nights. Other things may play a part.", style = AppType.footnote, color = c.secondaryLabel)
                        }
                    }
                }
            }
            if (onTest != null) {
                if (rows.isNotEmpty()) Hairline(Modifier.padding(start = AppSpace.l))
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptic.tick(); onTest() }.padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Test it yourself", style = AppType.body)
                        Text("Try one habit for 14 days and see what changes.", style = AppType.footnote, color = c.secondaryLabel)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel)
                }
            }
        }
        if (waiting.isNotEmpty()) Text(
            "Not enough days yet: " + waiting.joinToString(", ") { label(it.first) }, style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(horizontal = AppSpace.xs),
        )
    }
}

// Experiment kinds (backend insights.experiment) as the "do it" day's instruction; a "skip it" day reads "No …".
internal val TEST_ON = mapOf(
    "alcohol" to "Alcohol", "caffeine_late" to "Coffee after 14:00", "late_meal" to "Late meal", "workout" to "Workout",
    "breathe" to "Breathing session", "phone_in_bed" to "Phone in bed",
)
private val TEST_METRICS = listOf("sleep_score", "deep_min", "rem_min", "hrv", "rhr", "total_min", "awake_min", "bed_start")

/** Pick a habit and what to watch; [onStart] gets (kind, metric). [felt]: Guess first is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestDialog(felt: Boolean, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var kind by remember { mutableStateOf<String?>(null) }
    var metric by remember { mutableStateOf("deep_min") }
    @Composable fun chips(options: List<Pair<String, String>>, selected: String?, pick: (String) -> Unit) = FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s),
    ) {
        options.forEach { (k, label) ->
            val col = if (k == selected) c.tint else c.secondaryLabel
            Text(
                label, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = if (k == selected) c.tint else c.label,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f))
                    .clickable(role = Role.RadioButton) { haptic.tick(); pick(k) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
        title = { Text("Test it yourself", style = AppType.title) },
        confirmButton = {
            TextButton({ haptic.tick(); kind?.let { onStart(it, metric) } }, enabled = kind != null) { Text("Start", color = if (kind != null) c.tint else c.secondaryLabel) }
        },
        dismissButton = { TextButton({ haptic.tick(); onDismiss() }) { Text("Cancel", color = c.tint) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Habit", style = AppType.footnote, color = c.secondaryLabel)
                chips(TEST_ON.toList(), kind) { kind = it }
                Text("What to watch", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
                // "felt" needs morning guesses: offered only while Guess first is on.
                chips((if (felt) TEST_METRICS + "felt" else TEST_METRICS).map { m -> m to METRIC.getValue(m).second.replaceFirstChar { it.uppercase() } }, metric) { metric = it }
                Text(
                    "14 days: 7 with it and 7 without, in a random order. A day counts when your journal matches the plan.",
                    style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s),
                )
            }
        },
    )
}

/** `/experiments.active`: running = today's instruction over the coin strip; done = the delta, one sentence, "Run again" / "Done". */
@Composable
private fun ExperimentCard(x: JsonObject, onAgain: () -> Unit, onDone: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val kind = x["kind"].str().orEmpty()
    val metric = x["metric"].str().orEmpty()
    val plan = x["plan"].str().orEmpty()
    val on = TEST_ON[kind] ?: kind
    val name = METRIC[metric]?.second ?: metric
    val done = x["status"].str() == "done"
    fun days(k: String) = (x[k] as? JsonArray)?.mapNotNull { it.num()?.toInt() }.orEmpty().toSet()
    // Yesterday is listed as skipped until its night has synced: not a miss yet.
    val pending = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(x["start"].str()), LocalDate.now()).toInt() - 1 }.getOrNull()
    val kept = days("kept")
    val crossed = days("skipped") - setOfNotNull(pending)
    val today = if (done) null else (x["day"].num()?.toInt() ?: 1) - 1
    val counted = "${kept.size} of ${kept.size + crossed.size} days counted"
    val instruction = (if (x["today"].str() == "on") "" else "no ") + on.replaceFirstChar { it.lowercase() }
    Section("Experiment") {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            if (today != null) {
                Row {
                    Text("Day ${today + 1} of ${plan.length} · watching $name", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                    // Ends the test the same way "Done" does, so a mis-started one does not block the next for 14 days.
                    Text("Stop", style = AppType.footnote, color = c.tint, modifier = Modifier.padding(start = AppSpace.m).clickable(role = Role.Button) { haptic.tick(); onDone() })
                }
                Text("Today: $instruction", style = AppType.headline)
            } else {
                Text("$on · $name", style = AppType.footnote, color = c.secondaryLabel)
                val e = (x["result"].obj()?.get("all") as? JsonArray)?.firstOrNull().obj()
                val d = e?.get("delta").num()
                val strong = e?.get("strong").str() == "true"
                if (e != null && d != null) {
                    // "−20 min" → sign, counted-up number, unit.
                    val s = metricDelta(metric, d, e["pct"].num())
                    val sign = s.takeWhile { !it.isDigit() }
                    val n = s.drop(sign.length).takeWhile { it.isDigit() }
                    val col = if (strong) effectColor(metric, d) else c.secondaryLabel
                    val style = if (strong) AppType.display else AppType.title
                    Row {
                        Text(sign, style = style, color = col, modifier = Modifier.alignByBaseline())
                        CountUp(n.toIntOrNull() ?: 0, style, Modifier.alignByBaseline(), color = col)
                        Text(s.drop(sign.length + n.length), style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.alignByBaseline())
                    }
                    sentence(kind, e)?.let { Text(it, style = AppType.subhead) }
                }
                if (!strong) Text("No clear difference in this test.", style = AppType.subhead, color = c.secondaryLabel)
            }
            CoinStrip(
                plan, kept, crossed, today,
                (if (today != null) "Day ${today + 1} of ${plan.length}, today: $instruction" else "${plan.length}-day plan") +
                    if (kept.size + crossed.size > 0) ". $counted, ${crossed.size} skipped." else ".",
            )
            if (kept.size + crossed.size > 0) Text(counted, style = AppType.footnote, color = c.secondaryLabel)
            if (done) Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                TintButton("Run again", Modifier.weight(1f)) { haptic.tick(); onAgain() }
                TintButton("Done", Modifier.weight(1f)) { haptic.tick(); onDone() }
            }
        }
    }
}

/** The week before a "sick" day as dots: calm on the line, the last [days] walking up to the day itself. */
@Composable
private fun LeadStrip(days: Int) {
    val c = AppTheme.colors
    val reduce = LocalReduceMotion.current
    val rise = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(rise) { rise.animateTo(1f, tween(600)) }
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .semantics { contentDescription = "Seven days before feeling sick: signals raised on the last $days." },
    ) {
        val slot = size.width / 8
        val r = minOf(slot * 0.3f, 5.dp.toPx())
        val base = size.height - r * 1.4f
        for (i in 0..7) {
            val step = (i - 6 + days).coerceAtLeast(0) // 0 = calm, days + 1 = the sick day
            val o = Offset(slot * (i + 0.5f), base - (base - r * 1.4f) * rise.value * step / (days + 1))
            drawCircle(if (i == 7) c.signalHigh else if (step > 0) c.signalElevated else c.signalCalm, if (i == 7) r * 1.4f else r, o)
        }
    }
}

/** One coin per plan day: filled = do it, hollow = skip it; counted days solid, days to come faint, a skipped day a faint cross; today's halo pulses. */
@Composable
private fun CoinStrip(plan: String, kept: Set<Int>, crossed: Set<Int>, today: Int?, describe: String) {
    val c = AppTheme.colors
    val pulse = if (today == null || LocalReduceMotion.current) null
        else rememberInfiniteTransition(label = "coin").animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse), label = "coin")
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = describe }) {
        val slot = size.width / plan.length.coerceAtLeast(1)
        val r = minOf(slot * 0.36f, 7.dp.toPx())
        val w = r * 0.3f
        plan.forEachIndexed { i, p ->
            val o = Offset(slot * (i + 0.5f), size.height / 2)
            if (i in crossed) {
                val k = r * 0.6f
                val faint = c.secondaryLabel.copy(alpha = 0.45f)
                drawLine(faint, Offset(o.x - k, o.y - k), Offset(o.x + k, o.y + k), w, StrokeCap.Round)
                drawLine(faint, Offset(o.x - k, o.y + k), Offset(o.x + k, o.y - k), w, StrokeCap.Round)
                return@forEachIndexed
            }
            val col = c.tint.copy(alpha = if (i in kept || i == today) 1f else 0.35f)
            if (p == '1') drawCircle(col, r, o) else drawCircle(col, r - w / 2, o, style = Stroke(w))
            if (i == today) drawCircle(c.tint.copy(alpha = 0.3f), r + w * (1.2f + 0.8f * (pulse?.value ?: 0.5f)), o, style = Stroke(w))
        }
    }
}

/** Short name (pills, tables) and sentence name per insight metric. */
internal val METRIC = mapOf(
    "sleep_score" to ("Sleep score" to "sleep score"), "deep_min" to ("Deep" to "deep sleep"), "rem_min" to ("REM" to "REM sleep"),
    "hrv" to ("HRV" to "HRV"), "rhr" to ("Resting HR" to "resting heart rate"), "total_min" to ("Time asleep" to "time asleep"),
    "awake_min" to ("Awake" to "time awake"), "bed_start" to ("Bedtime" to "bedtime"), "strain" to ("Strain" to "strain index"),
    "day_hr" to ("Daytime HR" to "daytime heart rate"), "wind_down" to ("Settling" to "settling time"),
    "felt" to ("Felt" to "how recovered you felt"), "stress_load" to ("Stress" to "stress load"), "steps" to ("Steps" to "steps"),
)


/** bed_start and wind_down are minutes from midnight (23:30 = -30). */
internal fun bedClock(m: Double): String = clock(LocalTime.MIDNIGHT.plusMinutes(m.roundToInt().toLong()))

/** A value in its unit: "52 min", "7 h 12 min", "11:40 PM", "58 bpm", "78". */
internal fun metricValue(metric: String, v: Double): String = when (metric) {
    "bed_start", "wind_down" -> bedClock(v)
    "total_min" -> duration(v.roundToInt())
    "strain" -> "%.1f".format(v)
    "stress_load" -> "%.1f h".format(v)
    "steps" -> "%,d".format(v.roundToInt())
    "sleep_score", "felt" -> v.roundToInt().toString()
    "hrv" -> "${v.roundToInt()} ms"
    "rhr", "day_hr" -> "${v.roundToInt()} bpm"
    else -> "${v.roundToInt()} min"
}

/** Signed change: "−25 min", "+3 bpm"; HRV as a percent when [pct] is given. */
internal fun metricDelta(metric: String, d: Double, pct: Double?): String {
    val a = kotlin.math.abs(d)
    val body = when {
        metric == "hrv" && pct != null -> "${kotlin.math.abs(pct).roundToInt()} %"
        metric == "strain" -> "%.1f".format(a)
        metric == "sleep_score" || metric == "felt" -> "${a.roundToInt()} pts"
        metric == "bed_start" || metric == "wind_down" || metric == "total_min" -> "${a.roundToInt()} min"
        else -> metricValue(metric, a)
    }
    return if (body.startsWith("0 ") || body.startsWith("0.0") || body == "0") body else (if (d < 0) "−" else "+") + body // no "−0 ms"
}

/** One top effect of a marked thing against the user's other days: "The night before, you slept 52 min less than usual." */
private fun markSentence(e: JsonObject): String? {
    val m = e["metric"].str() ?: return null
    val d = e["delta"].num() ?: return null
    val a = kotlin.math.abs(d)
    val min = duration(a.roundToInt())
    val more = if (d > 0) "more" else "less"
    return when (m) {
        "total_min" -> "The night before, you slept $min $more than usual."
        "awake_min" -> "The night before, you were awake $min $more than usual."
        "deep_min" -> "The night before, you got $min $more deep sleep than usual."
        "bed_start" -> "The night before, you fell asleep $min ${if (d > 0) "later" else "earlier"} than usual."
        "steps" -> "The day before, you took ${metricValue(m, a)} ${if (d > 0) "more" else "fewer"} steps than usual."
        "felt" -> "That morning, you felt ${a.roundToInt()} pts $more recovered than usual."
        else -> "The ${if (m == "stress_load") "day" else "night"} before, your ${METRIC[m]?.second ?: m} was ${metricDelta(m, a, e["pct"].num()).removePrefix("+")} ${if (d > 0) "higher" else "lower"} than usual."
    }
}

/** What comes before one thing the user named (`/marks.things[]`): its name, the clock line of its marks and the sentences. Read-only; marking is on Today. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkInsight(t: JsonObject) {
    val c = AppTheme.colors
    val times = (t["marks"] as? JsonArray)?.mapNotNull { it.num()?.toLong()?.times(1000) }.orEmpty()
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            Text(t["name"].str().orEmpty(), style = AppType.headline)
            if (times.isNotEmpty()) ClockLine(times)
            if (t["status"].str() != "ok") Text(
                "Mark it on 3 days to see what comes before. ${t["n_with"].num()?.roundToInt() ?: 0} so far.", style = AppType.subhead, color = c.secondaryLabel,
            ) else {
                val top = (t["top"] as? JsonArray)?.mapNotNull { it.obj() }.orEmpty()
                if (top.isEmpty()) Text("Nothing stands out before your marks so far.", style = AppType.subhead, color = c.secondaryLabel)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    top.forEach { e ->
                        val m = e["metric"].str().orEmpty()
                        val d = e["delta"].num() ?: 0.0
                        val col = effectColor(m, d)
                        Text(
                            "${METRIC[m]?.first ?: m} ${metricDelta(m, d, e["pct"].num())}",
                            style = AppType.footnote.copy(fontWeight = FontWeight.SemiBold), color = col,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                top.mapNotNull(::markSentence).forEach { Text(it, style = AppType.subhead) }
                t["pattern"].obj()?.let { p ->
                    val hits = p["hits"].num()?.roundToInt() ?: return@let
                    val others = (p["alike"].num()?.roundToInt() ?: return@let) - hits // `alike` counts the hits too
                    Text(
                        "${if (p["metric"].str() in setOf("steps", "stress_load")) "Days" else "Nights"} like that came before $hits of your ${p["marked"].num()?.roundToInt() ?: return@let} marks. " +
                            if (others == 1) "1 other passed without one." else "${if (others == 0) "No" else others} others passed without one.",
                        style = AppType.subhead,
                    )
                }
                Text("From your own days. Other things may play a part.", style = AppType.footnote, color = c.secondaryLabel)
            }
        }
    }
}

/** The clock line: 24 hours left to right, one dot per mark at its time of day; marks close in time stack. */
@Composable
private fun ClockLine(times: List<Long>) {
    val c = AppTheme.colors
    // 32 slots of 45 minutes: marks in one slot stack. Five dots of room at most; a taller stack stays at the top.
    val stacks = times.groupingBy { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay() / 2700 }.eachCount()
    val describe = "A 24-hour line with one dot per mark: ${times.size} ${if (times.size == 1) "mark" else "marks"}, the latest at ${clock(times.max() / 1000.0)}."
    Column {
        Canvas(Modifier.fillMaxWidth().height((12 + 9 * stacks.values.max().coerceAtMost(5)).dp).semantics { contentDescription = describe }) {
            val w = 1.dp.toPx()
            val r = minOf(4.dp.toPx(), size.width / 64 - w / 2)
            val base = size.height - 6.dp.toPx()
            drawLine(c.secondaryLabel, Offset(0f, base), Offset(size.width, base), w)
            for (h in 0..24 step 6) (w / 2 + (size.width - w) * h / 24).let { x -> drawLine(c.secondaryLabel, Offset(x, base), Offset(x, size.height), w) }
            stacks.forEach { (slot, n) ->
                repeat(n) { i -> drawCircle(c.tint, r, Offset(size.width * (slot + 0.5f) / 32, maxOf(base - 2 * w - r - i * (2 * r + w), r))) }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            (if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) listOf("00", "06", "12", "18", "24") else listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM"))
                .forEach { Text(it, style = AppType.tab, color = c.secondaryLabel) }
        }
    }
}
