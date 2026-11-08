package dev.ring.health.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val NAMES = mapOf(
    "total" to "Total sleep", "efficiency" to "Efficiency", "restfulness" to "Restfulness", "rem" to "REM sleep",
    "deep" to "Deep sleep", "timing" to "Timing", "regularity" to "Regularity", "resting_hr" to "Resting heart rate", "hrv_balance" to "HRV balance",
    "sleep" to "Sleep", "sleep_balance" to "Sleep balance", "previous_day_activity" to "Previous day activity",
    "activity_balance" to "Activity balance", "recovery_index" to "Recovery index", "steps" to "Steps",
    "inactive_hours" to "Inactive hours", "calories" to "Calories", "goal_days" to "Goal days",
)

// Plain-language explanations shown when a score or contributor is tapped. Keep in sync with backend/app/scoring.py.
private val SCORE_INFO = mapOf(
    "sleep" to "How well you slept last night, from 0 to 100. It is the average of the contributors below: " +
        "time asleep against your sleep need, how much of the night you were asleep, minutes awake, REM and deep " +
        "sleep, whether the middle of your sleep fell between midnight and 3 AM and how regular your sleep times were.",
    "readiness" to "How recovered you are for today, from 0 to 100. It compares last night's resting heart rate " +
        "and HRV with your own 14-day baseline, and adds last night's sleep, your recent sleep and activity, " +
        "yesterday's steps and how quickly your heart rate settled overnight.",
    "activity" to "How active you have been today, from 0 to 100. It averages your steps against your step goal, " +
        "the awake hours you barely moved, your calories against your 7-day average and how many of the last " +
        "7 days reached your goal.",
)
private const val SCORE_SCALE = "80 or more is good, 60–79 fair, below 60 low. A contributor shows “—” until " +
    "there is enough data; the score uses the ones available. Scores are estimates from your ring, compared " +
    "with your own history, not medical advice. Tap a row for details."

private val CONTRIBUTOR_INFO = mapOf(
    "total" to "Time asleep against your sleep need (Settings). 100 at or above it, 0 at 4 hours.",
    "efficiency" to "Share of the night spent asleep rather than awake. 100 at 95% or more, 0 at 75%.",
    "restfulness" to "Minutes awake during the night. 100 at 10 minutes or less, 0 at an hour.",
    "rem" to "Minutes of REM (dream) sleep. 100 at 90 minutes or more.",
    "deep" to "Minutes of deep sleep. 100 at 90 minutes or more.",
    "timing" to "When the middle of your sleep fell. 100 between midnight and 3 AM, about 33 less per hour outside.",
    "regularity" to "How much the middle of your sleep moved over the last 7 nights. 100 within 30 minutes, 0 at 2 hours; needs 4 nights.",
    "resting_hr" to "Your low overnight heart rate against your 14-day baseline. 100 at or below it, 10 less per bpm above.",
    "hrv_balance" to "Your overnight heart rate variability against your 14-day baseline. 100 at or above it, 0 at 60% of it.",
    "sleep" to "Last night's sleep score.",
    "sleep_balance" to "Your average sleep over the last 14 days against your sleep need.",
    "previous_day_activity" to "Yesterday's steps against your goal. 100 between 80% and 120%, lower when far below or above.",
    "activity_balance" to "Your average daily steps over the last 14 days against your goal.",
    "recovery_index" to "How soon your heart rate settled overnight. 100 if it reached its low in the first half of the night.",
    "steps" to "Today's steps against your step goal (Settings).",
    "inactive_hours" to "Awake hours with fewer than 50 steps. 100 at 5 hours or fewer, 0 at 12.",
    "calories" to "Today's calories against your 7-day average. 100 at or above it.",
    "goal_days" to "Days in the last 7 that reached your step goal. 100 at 5 days or more.",
)

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(backend: Boolean?, journalDate: String? = null, onJournalOpened: () -> Unit = {}) {
    if (backend != true) return PlaceholderTab("Today", backend)
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    // The tab leaves composition when deselected, so this also reloads on reselect.
    var journal by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var medTime by remember { mutableStateOf("08:00") }
    var medStarted by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var sheetDay by remember { mutableStateOf<Int?>(null) } // 0 Yesterday, 1 Today; null = by time of day
    var insights by remember { mutableStateOf<JsonObject?>(null) }
    var hr by remember { mutableStateOf<List<Double>>(emptyList()) }
    LaunchedEffect(journalDate) {
        if (journalDate == null) return@LaunchedEffect
        sheetDay = if (journalDate == LocalDate.now().toString()) 1 else 0
        sheet = true
        onJournalOpened()
    }
    val scope = rememberCoroutineScope()
    suspend fun loadJournal() { journal = parseJournal(Api.journal(ctx, 2)) }
    LaunchedEffect(Unit) {
        data = Api.today(ctx).obj()
        loadJournal()
        Api.settings(ctx)?.let { s -> s["medication_time"].str()?.let { medTime = it }; medStarted = s["medication_started"].str() }
        insights = Api.insights(ctx).obj()?.get("kinds").obj()
        hr = (Api.hr(ctx, 24).obj()?.get("points") as? JsonArray)?.mapNotNull { it.obj()?.get("bpm").num() }.orEmpty()
    }

    val c = AppTheme.colors
    LargeTitleScaffold("Today") {
        val d = data ?: return@LargeTitleScaffold
        item {
            val h = LocalTime.now().hour
            Column(Modifier.padding(start = AppSpace.xs)) {
                Text(if (h in 5..11) "Good morning" else if (h in 12..17) "Good afternoon" else "Good evening", style = AppType.subhead, color = c.secondaryLabel)
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), style = AppType.headline)
            }
        }
        // Ring until the 14-day baseline is full (backend "learning" ends at 5 days, when baselines start).
        val n = d["baseline_days"].str()?.substringBefore('/')?.toIntOrNull() ?: 0
        if (n < 14) item {
            InsetCard {
                Row(Modifier.padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(n / 14f, c.tint, Modifier.size(56.dp), stroke = 7.dp) { Text("$n", style = AppType.headline) }
                    Column(Modifier.padding(start = AppSpace.l)) {
                        Text("Day $n of 14", style = AppType.headline)
                        Text("Learning your normal. Scores settle once there are 14 days.", style = AppType.subhead, color = c.secondaryLabel)
                    }
                }
            }
        }
        item {
            val r = d["readiness"].obj()
            val score = r?.get("score").num()?.roundToInt()
            val mood = when { score == null -> c.moodFair.copy(alpha = 0f); score >= 80 -> c.moodGood; score >= 60 -> c.moodFair; else -> c.moodLow }
            InsetCard {
                Column(
                    Modifier.fillMaxWidth().background(Brush.radialGradient(listOf(mood, mood.copy(alpha = 0f))))
                        .clickable { open = if (open == "readiness") null else "readiness" }.padding(AppSpace.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScoreRing((score ?: 0) / 100f, c.readiness, Modifier.size(220.dp), stroke = 20.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (score != null) CountUp(score, AppType.display) else Text("—", style = AppType.display)
                            Text("Readiness", style = AppType.subhead, color = c.secondaryLabel)
                        }
                    }
                    if (score != null) Text(
                        coachLine(score, r?.get("contributors").obj()), style = AppType.subhead, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = AppSpace.l),
                    )
                }
                ScoreDetails("readiness", "Readiness", c.readiness, d, open == "readiness", medStarted)
            }
        }
        item {
            val small = listOf("sleep" to ("Sleep" to c.sleep), "activity" to ("Activity" to c.activity))
            InsetCard {
                Row(Modifier.padding(horizontal = AppSpace.m, vertical = AppSpace.l)) {
                    small.forEach { (key, meta) ->
                        val score = d[key].obj()?.get("score").num()?.roundToInt()
                        Column(
                            Modifier.weight(1f).clip(AppShape.small).clickable { open = if (open == key) null else key }.padding(AppSpace.s),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ScoreRing((score ?: 0) / 100f, meta.second, Modifier.size(96.dp), stroke = 10.dp) {
                                if (score != null) CountUp(score, AppType.title) else Text("—", style = AppType.title)
                            }
                            Text(
                                meta.first, style = AppType.footnote, modifier = Modifier.padding(top = AppSpace.s),
                                color = if (open == key) c.label else c.secondaryLabel,
                                fontWeight = if (open == key) FontWeight.SemiBold else null,
                            )
                        }
                    }
                }
                small.forEach { (key, meta) -> ScoreDetails(key, meta.first, meta.second, d, open == key, medStarted) }
            }
        }
        d["signals"].obj()?.let { item { BodySignals(it, medStarted) } }
        item {
            val v = d["vitals"].obj()
            val tiles = listOf("Heart rate" to ("hr" to "bpm"), "HRV" to ("hrv" to "ms"), "SpO₂" to ("spo2" to "%"), "Ring battery" to ("battery" to "%"))
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Vitals", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        pair.forEach { (label, m) ->
                            val x = v?.get(m.first).num()
                            InsetCard(Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.padding(AppSpace.l)) {
                                    Text(label, style = AppType.footnote, color = c.secondaryLabel)
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(x?.roundToInt()?.toString() ?: "—", style = AppType.title)
                                        if (x != null) Text(" ${m.second}", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(bottom = 3.dp))
                                    }
                                    if (m.first == "hr" && hr.size > 1) Sparkline(hr, c.heart, Modifier.padding(top = AppSpace.s).fillMaxWidth().height(40.dp))
                                }
                            }
                        }
                    }
                }
                val w = d["wear"].obj()
                val off = ((w?.get("awake_hours").num() ?: 0.0) - (w?.get("worn_hours").num() ?: 0.0)).roundToInt()
                if (off >= 2) Text("Ring off for $off h today", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.xs))
            }
        }
        item {
            val today = LocalDate.now()
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Journal", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    if (journal.isEmpty()) Text(
                        "Log what you did and we'll show how it affects your sleep and recovery.",
                        style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l, end = AppSpace.l),
                    )
                    listOf(today.minusDays(1) to "Yesterday", today to "Today").forEach { (day, label) ->
                        val es = journal.filter { it.date == day.toString() }.sortedWith(compareBy({ KIND_LABEL.keys.indexOf(it.kind) }, { it.value }))
                        if (es.isNotEmpty()) {
                            Text(label, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l))
                            FlowRow(
                                Modifier.padding(start = AppSpace.l, top = AppSpace.s, end = AppSpace.l),
                                horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s),
                            ) { es.forEach { JournalChip(it.label()) } }
                        }
                    }
                    TintButton(if (LocalTime.now().hour < 12) "Log yesterday" else "Log today", Modifier.padding(AppSpace.l)) { sheet = true }
                }
            }
        }
        // Kinds the user has logged at least once; never-logged kinds would all read "log 3 more days".
        val kinds = insights?.let { k -> AFFECT_KINDS.mapNotNull { kind -> k[kind].obj()?.takeIf { it["status"].str() == "ok" || (it["n_with"].num() ?: 0.0) > 0 }?.let { kind to it } } }
        if (!kinds.isNullOrEmpty()) item { WhatAffectsYou(kinds) }
    }
    if (sheet) CheckInSheet(journal, medTime, scope, sheetDay, ::loadJournal) { sheet = false; sheetDay = null }
}

/** Explanation + contributor rows under a score; the readiness one adds the medication-baseline note. */
@Composable
private fun ScoreDetails(key: String, name: String, color: Color, d: JsonObject, visible: Boolean, medStarted: String?) {
    val c = AppTheme.colors
    AnimatedVisibility(visible) {
        Column {
            Hairline(Modifier.padding(start = AppSpace.l))
            Column(Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
                Text("What is $name?", style = AppType.headline)
                Text(SCORE_INFO[key].orEmpty(), style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.xs))
                Text(SCORE_SCALE, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
            }
            (d[key].obj()?.get("contributors").obj() ?: JsonObject(emptyMap())).forEach { (n, v) ->
                Hairline(Modifier.padding(start = AppSpace.l))
                Contributor(NAMES[n] ?: n, v.num(), color, CONTRIBUTOR_INFO[n])
            }
            val since = medStarted?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now()) }.getOrNull() }
            if (key == "readiness" && since != null && since in 0..60) {
                Hairline(Modifier.padding(start = AppSpace.l))
                Text(
                    "Resting HR is compared with your 14-day baseline, which now includes days on medication; it settles after about two weeks.",
                    style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m),
                )
            }
        }
    }
}

// Readiness contributors in words (app twin of backend notify.WORDS), said when that contributor is the lowest.
private val LOW_WORDS = mapOf(
    "resting_hr" to "Your resting heart rate is higher than usual.",
    "hrv_balance" to "Your heart rate variability is lower than usual.",
    "sleep" to "Last night's sleep was on the light side.",
    "sleep_balance" to "You have slept less than you need lately.",
    "previous_day_activity" to "Yesterday was far from your step goal.",
    "activity_balance" to "Your recent activity is off your goal.",
    "recovery_index" to "Your heart rate took a while to settle overnight.",
)

/** Band sentence, plus the lowest contributor when it is below 80. */
private fun coachLine(score: Int, contributors: JsonObject?): String {
    val band = if (score >= 80) "Well recovered. A good day to push." else if (score >= 60) "Recovered enough. Keep it steady." else "Take it easy today."
    val low = contributors?.mapNotNull { (k, v) -> v.num()?.let { k to it } }?.minByOrNull { it.second }?.takeIf { it.second < 80 }
    return listOfNotNull(band, low?.let { LOW_WORDS[it.first] }).joinToString(" ")
}

/** 24-h heart-rate line scaled to its own min..max. */
@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val lo = values.min()
        val span = (values.max() - lo).coerceAtLeast(1.0)
        val w = 2.dp.toPx()
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = w + (size.height - 2 * w) * (1 - (v - lo) / span).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** One journal row from `/journal`. */
private class Entry(val id: Int, val date: String, val kind: String, val value: String?, val note: String?) {
    fun label() = (KIND_LABEL[kind] ?: kind) + if (kind == "bp" || kind == "medication") " ${value.orEmpty()}" else ""
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private fun parseJournal(json: JsonElement?): List<Entry> = (json.obj()?.get("entries") as? JsonArray)?.mapNotNull { it.obj() }?.mapNotNull {
    Entry(it["id"].str()?.toIntOrNull() ?: return@mapNotNull null, it["date"].str().orEmpty(), it["kind"].str().orEmpty(), it["value"].str(), it["note"].str())
} ?: emptyList()

/** Short names for chips and the Trends summary line. */
internal val KIND_LABEL = mapOf(
    "alcohol" to "Alcohol", "caffeine_late" to "Late caffeine", "late_meal" to "Late meal", "workout" to "Workout",
    "sick" to "Sick", "travel" to "Travel", "stress" to "Stress", "medication" to "Medication", "bp" to "BP", "note" to "Note",
)

private val QUESTIONS = listOf(
    "alcohol" to "Alcohol?", "caffeine_late" to "Caffeine after 2 PM?", "late_meal" to "Late meal?", "workout" to "Workout?",
    "sick" to "Felt sick?", "travel" to "Travelled?", "stress" to "Stressful day?",
)

private val WHY = mapOf(
    "alcohol" to "Alcohol tends to raise your night-time heart rate and cut deep and REM sleep.",
    "caffeine_late" to "Caffeine stays in the body for 5–6 hours and can delay falling asleep.",
    "late_meal" to "Eating close to bedtime can keep your heart rate up overnight.",
    "workout" to "Hard training can show up in the next night's HRV and resting heart rate.",
    "sick" to "Being unwell can raise your resting heart rate and lower your HRV.",
    "travel" to "Travel and time-zone changes shift when you sleep.",
    "stress" to "A stressful day can lower your HRV and make sleep lighter.",
    "medication" to "Dose times let us mark doses on the Heart screen and compare days with and without a dose.",
    "bp" to "From a cuff reading; the ring cannot measure blood pressure.",
    "note" to "Anything else worth remembering about the day.",
)

@Composable
private fun JournalChip(text: String) {
    val c = AppTheme.colors
    Text(
        text, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.label,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.secondaryLabel.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** iOS tinted button: tint text on a faint tint fill. */
@Composable
private fun TintButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    Text(
        text, style = AppType.headline, color = c.tint, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().clip(AppShape.small).background(c.tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(vertical = AppSpace.m),
    )
}

/** Writes go to [scope] (outlives the sheet) so a pending note or BP is saved when the sheet closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckInSheet(entries: List<Entry>, medTime: String, scope: CoroutineScope, startDay: Int?, reload: suspend () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var day by remember { mutableIntStateOf(startDay ?: if (LocalTime.now().hour < 12) 0 else 1) }
    // Full height: a half-open sheet snaps shut when rows appear (dose, second dose).
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = AppSpace.l).padding(bottom = AppSpace.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpace.m),
        ) {
            Text("Check-in", style = AppType.title)
            Segmented(listOf("Yesterday", "Today"), day) { if (it != day) { day = it; haptic.tick() } }
            val date = LocalDate.now().minusDays(1L - day).toString()
            key(date) { DayForm(date, entries.filter { it.date == date }, medTime, scope, reload) }
        }
    }
}

private fun bpOk(s: String): Boolean {
    val n = s.split('/').map { it.toIntOrNull() ?: return false }
    return n.size == 2 && n[0] in 70..250 && n[1] in 40..150
}

@Composable
private fun DayForm(date: String, es: List<Entry>, medTime: String, scope: CoroutineScope, reload: suspend () -> Unit) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    fun run(block: suspend () -> Unit) { scope.launch { block(); reload() } }

    InsetCard {
        QUESTIONS.forEachIndexed { i, (kind, q) ->
            if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
            val e = es.firstOrNull { it.kind == kind }
            ToggleRow(q, WHY[kind], e != null) { on ->
                haptic.tick()
                run { if (on) Api.addJournal(ctx, date, kind) else e?.let { Api.deleteJournal(ctx, it.id) } }
            }
        }
    }

    val doses = es.filter { it.kind == "medication" }.sortedBy { it.value }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    InsetCard {
        ToggleRow("Medication?", WHY["medication"], doses.isNotEmpty()) { on ->
            haptic.tick()
            run { if (on) Api.addJournal(ctx, date, "medication", medTime) else doses.forEach { Api.deleteJournal(ctx, it.id) } }
        }
        doses.forEachIndexed { i, d ->
            Hairline(Modifier.padding(start = AppSpace.l))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                Text(if (i == 0) "Dose" else "Second dose", style = AppType.body, modifier = Modifier.weight(1f))
                if (i > 0) Text(
                    "Remove", style = AppType.subhead, color = c.activity,
                    modifier = Modifier.clip(AppShape.small).clickable { run { Api.deleteJournal(ctx, d.id) } }.padding(AppSpace.s),
                )
                Text(
                    d.value.orEmpty(), style = AppType.body, color = c.tint,
                    modifier = Modifier.clip(AppShape.small).clickable { editing = d }.padding(AppSpace.s),
                )
            }
        }
        if (doses.size == 1) {
            Hairline(Modifier.padding(start = AppSpace.l))
            Text(
                "+ Second dose", style = AppType.body, color = c.tint,
                modifier = Modifier.fillMaxWidth().clickable { adding = true }.padding(AppSpace.l),
            )
        }
    }
    editing?.let { d ->
        TimePickDialog(d.value ?: medTime, { editing = null }) { t ->
            editing = null
            // Add before delete so a failed write never loses the dose.
            if (t != d.value) run { if (Api.addJournal(ctx, date, "medication", t) != null) Api.deleteJournal(ctx, d.id) }
        }
    }
    if (adding) {
        val second = runCatching { LocalTime.parse(doses.first().value).plusHours(4).toString() }.getOrDefault(medTime)
        TimePickDialog(second, { adding = false }) { t -> adding = false; run { Api.addJournal(ctx, date, "medication", t) } }
    }

    val bp = es.firstOrNull { it.kind == "bp" }?.value.orEmpty()
    var sys by remember { mutableStateOf(bp.substringBefore('/', "")) }
    var dia by remember { mutableStateOf(bp.substringAfter('/', "")) }
    var note by remember { mutableStateOf(es.firstOrNull { it.kind == "note" }?.note.orEmpty()) }
    val saved = remember { mutableMapOf("bp" to bp, "note" to note) }
    val latest by rememberUpdatedState(es)
    fun save(kind: String, text: String) {
        if (saved[kind] == text || (kind == "bp" && text.isNotEmpty() && !bpOk(text))) return
        saved[kind] = text
        val old = latest.firstOrNull { it.kind == kind }
        scope.launch {
            when {
                text.isEmpty() -> old?.let { Api.deleteJournal(ctx, it.id) }
                kind == "bp" -> Api.addJournal(ctx, date, "bp", text)
                else -> Api.addJournal(ctx, date, "note", note = text)
            }
            reload()
        }
    }
    val bpText = if (sys.isEmpty() && dia.isEmpty()) "" else "$sys/$dia"
    val noteText = note.trim()
    LaunchedEffect(bpText) { delay(1200); save("bp", bpText) }
    LaunchedEffect(noteText) { delay(1200); save("note", noteText) }
    val bpNow by rememberUpdatedState(bpText)
    val noteNow by rememberUpdatedState(noteText)
    DisposableEffect(Unit) { onDispose { save("bp", bpNow); save("note", noteNow) } }

    InsetCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpace.l, vertical = AppSpace.s), verticalAlignment = Alignment.CenterVertically) {
            InfoLabel("Blood pressure", WHY["bp"], Modifier.weight(1f))
            NumField(sys, "SYS") { sys = it }
            Text(" / ", style = AppType.body, color = c.secondaryLabel)
            NumField(dia, "DIA") { dia = it }
        }
        if (bpText.isNotEmpty() && !bpOk(bpText)) Text(
            "Enter both numbers, for example 120 / 80.", style = AppType.footnote, color = c.activity,
            modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.s),
        )
        Hairline(Modifier.padding(start = AppSpace.l))
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
            InfoLabel("Note", WHY["note"])
            Box(Modifier.padding(top = AppSpace.xs)) {
                if (note.isEmpty()) Text("Anything else about the day", style = AppType.body, color = c.secondaryLabel)
                BasicTextField(
                    note, { note = it.take(200) }, textStyle = AppType.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.tint), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NumField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = AppTheme.colors
    BasicTextField(
        value, { onChange(it.filter(Char::isDigit).take(3)) }, singleLine = true,
        textStyle = AppType.body.copy(color = c.label, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(c.tint), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(52.dp).clip(AppShape.small).background(c.secondaryLabel.copy(alpha = 0.12f)).padding(vertical = AppSpace.s),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) Text(placeholder, style = AppType.body, color = c.secondaryLabel)
                inner()
            }
        },
    )
}

/** Label with a tappable ⓘ that reveals one line of why. */
@Composable
private fun InfoLabel(text: String, why: String?, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var show by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = AppType.body)
            if (why != null) Text(
                "ⓘ", style = AppType.body, color = c.tint,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { show = !show }.padding(horizontal = AppSpace.s, vertical = AppSpace.xs),
            )
        }
        AnimatedVisibility(show) { Text(why.orEmpty(), style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(end = AppSpace.s)) }
    }
}

@Composable
private fun ToggleRow(q: String, why: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Local state flips at once; the server state re-keys it after the reload.
    var on by remember(checked) { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpace.l, vertical = AppSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        InfoLabel(q, why, Modifier.weight(1f))
        Switch(on, { on = it; onChange(it) }, colors = iosSwitchColors())
    }
}

/** iOS switch: green track when on, white knob in both themes. */
@Composable
internal fun iosSwitchColors(): SwitchColors {
    val c = AppTheme.colors
    val knob = if (c.background.luminance() < 0.5f) c.label else c.card
    val off = c.secondaryLabel.copy(alpha = 0.3f)
    return SwitchDefaults.colors(
        checkedThumbColor = knob, checkedTrackColor = c.readiness, checkedBorderColor = c.readiness,
        uncheckedThumbColor = knob, uncheckedTrackColor = off, uncheckedBorderColor = off,
    )
}

/** 24 h time picker dialog; [onPick] gets "HH:MM". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    val t = runCatching { LocalTime.parse(initial) }.getOrDefault(LocalTime.of(8, 0))
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
    val knob = if (c.background.luminance() < 0.5f) c.label else c.card
    val faint = c.secondaryLabel.copy(alpha = 0.16f)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        confirmButton = { TextButton({ onPick("%02d:%02d".format(Locale.ROOT, state.hour, state.minute)) }) { Text("Done", color = c.tint) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.tint) } },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = faint, clockDialSelectedContentColor = knob, clockDialUnselectedContentColor = c.label,
                    selectorColor = c.tint, containerColor = c.card,
                    timeSelectorSelectedContainerColor = c.tint.copy(alpha = 0.16f), timeSelectorUnselectedContainerColor = faint,
                    timeSelectorSelectedContentColor = c.tint, timeSelectorUnselectedContentColor = c.label,
                ),
            )
        },
    )
}

// Wording mirrors backend/app/notify.py (_strain, LOGGED): says "may", never names an illness.
private val LOGGED = mapOf(
    "alcohol" to "alcohol", "workout" to "a workout", "sick" to "feeling unwell", "travel" to "travel",
    "stress" to "stress", "late_meal" to "a late meal",
)

private fun join(parts: List<String>) = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

private fun flagLine(k: String, delta: Double?, usual: Double?): String? = when (k) {
    "rhr" -> "Resting heart rate +${(delta ?: 0.0).roundToInt()} bpm above your usual"
    "hrv" -> if (delta != null && usual != null && usual > 0) "HRV ${(-100 * delta / usual).roundToInt()} % below your usual" else "HRV lower than usual"
    "awake" -> "Woke ${(delta ?: 0.0).roundToInt()} min more than usual"
    "recovery" -> "Heart rate settled late in the night"
    "spo2" -> "Blood oxygen dipped lower than usual"
    else -> null
}

@Composable
private fun BodySignals(s: JsonObject, medStarted: String?) {
    val c = AppTheme.colors
    var how by remember { mutableStateOf(false) }
    val nights = s["nights"].num()?.roundToInt() ?: 0
    val level = listOf("calm", "elevated", "high").indexOf(s["level"].str())
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("Body signals", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            Column(Modifier.fillMaxWidth().clickable { how = !how }.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                if (s["status"].str() == "learning" || level < 0) {
                    val med = s["reason"].str() == "medication" && medStarted != null
                    val on = if (med) runCatching { " since your medication started on " + LocalDate.parse(medStarted).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }.getOrDefault("") else ""
                    Text("Learning your usual values" + (if (nights < 7) " · $nights/7 nights" else "") + on, style = AppType.subhead, color = c.secondaryLabel)
                } else {
                    val steps = listOf(c.signalCalm, c.signalElevated, c.signalHigh)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                        steps.forEachIndexed { i, col ->
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(if (i <= level) col else c.secondaryLabel.copy(alpha = 0.16f)))
                        }
                        Text(
                            listOf("Calm", "Elevated", "High")[level], style = AppType.footnote, fontWeight = FontWeight.SemiBold,
                            color = c.label, modifier = Modifier.padding(start = AppSpace.s),
                        )
                    }
                    Text(listOf("Your body looks like itself.", "Some signs of strain.", "Clear signs of strain.")[level], style = AppType.headline)
                    val deltas = s["deltas"].obj()
                    val usual = s["usual"].obj()
                    s["flags"].obj()?.keys?.mapNotNull { flagLine(it, deltas?.get(it).num(), usual?.get(it).num()) }?.forEach {
                        Text(it, style = AppType.subhead)
                    }
                    val logged = (s["explain"] as? JsonArray)?.mapNotNull { LOGGED[it.str()] }.orEmpty()
                    if (level > 0 && logged.isNotEmpty()) Text("You logged ${join(logged)} yesterday, which can cause this.", style = AppType.subhead, color = c.secondaryLabel)
                    Text("Based on $nights nights. Not medical advice.", style = AppType.footnote, color = c.secondaryLabel)
                }
            }
            AnimatedVisibility(how) {
                Column {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    Column(Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
                        Text("How this works", style = AppType.headline)
                        Text(
                            "Last night's resting heart rate, HRV, time awake and overnight recovery are compared with your own last 14 nights. " +
                                "The ring has no temperature sensor, so this is a hint that your body may be under strain, not a prediction.",
                            style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.xs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Contributor(name: String, value: Double?, color: Color, info: String?) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
        Row {
            Text(name, style = AppType.body, modifier = Modifier.weight(1f))
            Text(value?.roundToInt()?.toString() ?: "—", style = AppType.body, color = if (value == null) c.secondaryLabel else c.label)
        }
        AnimatedVisibility(expanded && info != null) {
            Text(
                info.orEmpty() + if (value == null) " Not available yet: needs more days of data." else "",
                style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
            )
        }
        val bar = RoundedCornerShape(2.dp)
        Box(Modifier.padding(top = AppSpace.s).fillMaxWidth().height(4.dp).clip(bar).background(color.copy(alpha = 0.2f))) {
            if (value != null) Box(Modifier.fillMaxWidth((value / 100).coerceIn(0.0, 1.0).toFloat()).height(4.dp).clip(bar).background(color))
        }
    }
}

private val AFFECT_KINDS = listOf("alcohol", "caffeine_late", "late_meal", "workout", "stress", "travel", "sick")

private val AFTER = mapOf(
    "alcohol" to "after alcohol", "caffeine_late" to "after late caffeine", "late_meal" to "after a late meal",
    "workout" to "after a workout", "stress" to "after a stressful day", "travel" to "after travel",
)

/** Short name (pills, tables) and sentence name per insight metric. */
internal val METRIC = mapOf(
    "sleep_score" to ("Sleep score" to "sleep score"), "deep_min" to ("Deep" to "deep sleep"), "rem_min" to ("REM" to "REM sleep"),
    "hrv" to ("HRV" to "HRV"), "rhr" to ("Resting HR" to "resting heart rate"), "total_min" to ("Time asleep" to "time asleep"),
    "awake_min" to ("Awake" to "time awake"), "bed_start" to ("Bedtime" to "bedtime"), "strain" to ("Strain" to "strain index"),
    "day_hr" to ("Daytime HR" to "daytime heart rate"),
)

// 1 = higher is better, -1 = lower is better; bedtime is neither.
private val BETTER = mapOf("sleep_score" to 1, "deep_min" to 1, "rem_min" to 1, "hrv" to 1, "total_min" to 1, "rhr" to -1, "awake_min" to -1, "strain" to -1, "day_hr" to -1)

/** Pill / change colour: good, bad or neutral for this metric. */
@Composable
internal fun effectColor(metric: String, delta: Double): Color {
    val c = AppTheme.colors
    val dir = if (kotlin.math.abs(delta) < (if (metric == "strain") 0.05 else 0.5)) 0.0 else (BETTER[metric] ?: 0) * delta
    return if (dir > 0) c.signalCalm else if (dir < 0) c.signalHigh else c.secondaryLabel
}

/** bed_start is minutes from midnight (23:30 = -30). */
internal fun bedClock(m: Double): String = ((m.roundToInt() % 1440 + 1440) % 1440).let { "%02d:%02d".format(it / 60, it % 60) }

/** A value in its unit: "52 min", "7h 12m", "23:40", "58 bpm", "78". */
internal fun metricValue(metric: String, v: Double): String = when (metric) {
    "bed_start" -> bedClock(v)
    "total_min" -> v.roundToInt().let { "${it / 60}h ${it % 60}m" }
    "strain" -> "%.1f".format(v)
    "sleep_score" -> v.roundToInt().toString()
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
        metric == "sleep_score" -> "${a.roundToInt()} pts"
        metric == "bed_start" || metric == "total_min" -> "${a.roundToInt()} min"
        else -> metricValue(metric, a)
    }
    return if (body.startsWith("0 ") || body == "0.0") body else (if (d < 0) "−" else "+") + body // no "−0 ms"
}

private fun sentence(kind: String, e: JsonObject): String? {
    val m = e["metric"].str() ?: return null
    val w = e["with"].num() ?: return null
    val wo = e["without"].num() ?: return null
    if (kind == "sick") return "Around days you logged feeling unwell, your strain index averaged ${metricValue(m, w)} vs ${metricValue(m, wo)} on other nights."
    val on = "On nights ${AFTER[kind]}"
    return if (m == "bed_start") "$on you tended to fall asleep at ${bedClock(w)} vs ${bedClock(wo)}."
    else "$on your ${METRIC[m]?.second ?: m} averaged ${metricValue(m, w)} vs ${metricValue(m, wo)}."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatAffectsYou(kinds: List<Pair<String, JsonObject>>) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("What affects you", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            kinds.forEachIndexed { i, (kind, k) ->
                if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                val ok = k["status"].str() == "ok"
                var open by remember { mutableStateOf(false) }
                val nWith = k["n_with"].num()?.roundToInt() ?: 0
                val name = if (kind == "sick") "Feeling unwell" else KIND_LABEL[kind] ?: kind
                Column(Modifier.fillMaxWidth().clickable(enabled = ok) { open = !open }.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                    if (!ok) {
                        val need = 3 - nWith
                        val wo = 3 - (k["n_without"].num()?.roundToInt() ?: 0)
                        Text(
                            "$name · " + if (need > 0) "log $need more ${if (need == 1) "day" else "days"} to see the effect." else "needs $wo more ${if (wo == 1) "day" else "days"} without it.",
                            style = AppType.subhead, color = c.secondaryLabel,
                        )
                        return@Column
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = AppType.headline)
                        Text(" · $nWith ${if (kind == "sick") "days" else "nights"}", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                        Text(if (open) "Less" else "More", style = AppType.footnote, color = c.tint)
                    }
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
                    AnimatedVisibility(open) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                            (k["all"] as? JsonArray)?.mapNotNull { e -> e.obj()?.let { sentence(kind, it) } }?.forEach { Text(it, style = AppType.subhead) }
                            Text("Averages from your own nights. Other things may play a part.", style = AppType.footnote, color = c.secondaryLabel)
                        }
                    }
                }
            }
        }
    }
}
