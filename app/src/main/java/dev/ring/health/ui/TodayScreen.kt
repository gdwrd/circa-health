package dev.ring.health.ui

import android.media.MediaMetadata
import android.widget.Toast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import dev.ring.health.Hold
import dev.ring.health.PlaybackListener
import dev.ring.health.Prefs.holdDismissed
import dev.ring.health.Prefs.holdPlace
import dev.ring.health.Prefs.notificationAccess
import dev.ring.health.bookmark
import dev.ring.health.mark
import dev.ring.health.Prefs.markNames
import dev.ring.health.widget.MarkWidget
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import java.time.Instant
import java.time.ZoneId
import dev.ring.health.plays
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.glance.appwidget.updateAll
import dev.ring.health.Prefs.todayJson
import dev.ring.health.Prefs.todayJsonMs
import dev.ring.health.widget.RingWidget
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.PathEffect
import dev.ring.health.Prefs.suggestDismissed
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
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
private val VITALS_INFO = mapOf(
    "hr" to "Your latest reading from the ring, in beats per minute. The ring samples every 5 minutes while worn; the " +
        "line is the last 24 hours. Resting adults are usually around 50 to 80.",
    "hrv" to "Heart rate variability, the beat-to-beat variation in milliseconds, measured every 30 minutes. Higher " +
        "usually means better recovery. Compare it with your own baseline, not with other people.",
    "spo2" to "Blood oxygen saturation, measured hourly during sleep. 95 to 100 percent is normal. A single low reading " +
        "usually means a loose ring, not a problem.",
    "battery" to "Charge left in the ring. You get a reminder below 15 percent.",
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
fun TodayScreen(backend: Boolean?, onSettings: () -> Unit, onBreathe: () -> Unit, journalDate: String? = null, onJournalOpened: () -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    val gear = @Composable {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { haptic.tick(); onSettings() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Settings, "Settings", tint = AppTheme.colors.tint, modifier = Modifier.size(24.dp))
        }
    }
    if (backend != true) return PlaceholderTab("Today", backend, gear)
    // Breathe only with the backend: the session is saved there.
    val actions = @Composable {
        Row {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { haptic.tick(); onBreathe() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SelfImprovement, "Breathe", tint = AppTheme.colors.tint, modifier = Modifier.size(24.dp))
            }
            gear()
        }
    }
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }
    // The tab leaves composition when deselected, so this also reloads on reselect.
    var journal by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var medTime by remember { mutableStateOf("08:00") }
    var medStarted by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var sheetDay by remember { mutableStateOf<Int?>(null) } // 0 Yesterday, 1 Today; null = by time of day
    var insights by remember { mutableStateOf<JsonObject?>(null) }
    var exp by remember { mutableStateOf<JsonObject?>(null) } // `/experiments.active`
    var testing by remember { mutableStateOf(false) }
    var hr by remember { mutableStateOf<List<Double>>(emptyList()) }
    var hrv by remember { mutableStateOf<List<Double>>(emptyList()) }
    var hold by remember { mutableStateOf<Hold?>(null) }
    var holdGone by remember { mutableStateOf(ctx.holdDismissed) }
    var reply by remember { mutableStateOf<String?>(null) } // the feeling the NowCard answers
    var marks by remember { mutableStateOf<List<JsonObject>>(emptyList()) } // `/marks.things`: one per named thing
    LaunchedEffect(journalDate) {
        if (journalDate == null) return@LaunchedEffect
        sheetDay = if (journalDate == LocalDate.now().toString()) 1 else 0
        sheet = true
        onJournalOpened()
    }
    val scope = rememberCoroutineScope()
    suspend fun loadJournal() { Api.journal(ctx, 2)?.let { journal = parseJournal(it) } }
    suspend fun loadMarks() { (Api.marks(ctx).obj()?.get("things") as? JsonArray)?.let { a -> marks = a.mapNotNull { it.obj() } } }
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() {
        Api.today(ctx).obj()?.let { data = it }
        // Hold my place: last night's sleep onset against the playback log. Read here, on the phone; nothing about it is sent.
        hold = if (!ctx.holdPlace || !ctx.notificationAccess) null else Api.sleep(ctx, data?.get("date").str().orEmpty()).obj()?.get("session").obj()?.let { s ->
            val start = s["start"].num() ?: return@let null
            val end = s["end"].num() ?: return@let null
            bookmark(ctx.plays(), (start * 1000).toLong(), (end * 1000).toLong())
        }
        loadJournal()
        loadMarks()
        Api.settings(ctx)?.let { s ->
            s["medication_time"].str()?.let { medTime = it }; medStarted = s["medication_started"].str()
            val names = (1..3).map { s["mark_$it"].str().orEmpty() }
            if (names != ctx.markNames) { ctx.markNames = names; MarkWidget().updateAll(ctx) }
        }
        Api.insights(ctx).obj()?.let { insights = it["kinds"].obj() }
        Api.experiments(ctx).obj()?.let { exp = it["active"].obj() }
        (Api.hr(ctx, 24).obj()?.get("points") as? JsonArray)?.let { a -> hr = a.mapNotNull { it.obj()?.get("bpm").num() } }
        (Api.trends(ctx, "hrv", 14).obj()?.get("points") as? JsonArray)?.let { a -> hrv = a.mapNotNull { it.obj()?.get("value").num() } }
    }
    LaunchedEffect(Unit) { load() }
    // Guess first: both routes answer with the new /today. [widget]: its stored snapshot follows, so it stops saying "Guess first" at once.
    suspend fun answered(r: JsonObject?, widget: Boolean) {
        if (r == null) return Toast.makeText(ctx, "Not saved. Try again.", Toast.LENGTH_SHORT).show()
        data = r
        if (widget) { ctx.todayJson = r.toString(); ctx.todayJsonMs = System.currentTimeMillis(); RingWidget().updateAll(ctx) }
    }

    val c = AppTheme.colors
    LargeTitleScaffold("Today", action = actions, refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; try { load() } finally { refreshing = false } }
    }) {
        val d = data ?: return@LargeTitleScaffold
        item {
            val h = LocalTime.now().hour
            Column(Modifier.padding(start = AppSpace.xs)) {
                Text(if (h in 5..11) "Good morning" else if (h in 12..17) "Good afternoon" else "Good evening", style = AppType.subhead, color = c.secondaryLabel)
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), style = AppType.headline)
            }
        }
        val day = d["date"].str().orEmpty() // the server's day, never the phone's clock
        item { RightNow(day, scope, { reply = it }) { Api.today(ctx).obj()?.let { data = it } } }
        reply?.let { f -> item { NowCard(f, d, { text -> scope.launch { answered(Api.putNote(ctx, "now_$f", text), false) } }, onBreathe) { reply = null } } }
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
        val guess = d["guess"].obj()
        val calm = d["calm"].str() == "true"
        // Gate: nothing below is composed until the guess is in, so no ring, tint or coach line gives the answer away.
        if (guess?.get("pending").str() == "true") {
            item { GuessCard(calm) { v -> scope.launch { answered(Api.guess(ctx, day, v), true) } } }
            return@LargeTitleScaffold
        }
        val said = guess?.get("value").num()?.roundToInt()
        item {
            val r = d["readiness"].obj()
            val score = r?.get("score").num()?.roundToInt()
            val mood = when { score == null -> c.moodFair.copy(alpha = 0f); score >= 80 -> c.moodGood; score >= 60 -> c.moodFair; else -> c.moodLow }
            val low = d["confidence"].obj()?.get("readiness").str() == "low"
            InsetCard {
                Column(
                    Modifier.fillMaxWidth().background(Brush.radialGradient(listOf(mood, mood.copy(alpha = 0f))))
                        .clickable { haptic.tick(); open = if (open == "readiness") null else "readiness" }.padding(AppSpace.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Calm mode: the word until the card is tapped open, then the number with its details.
                    val words = calm && open != "readiness"
                    if (words) CalmArc(calmWord("readiness", score), if (low) 0.6f else 1f)
                    // The ring's own spring is the reveal after a guess; your dot marks what you said.
                    else ScoreRing((score ?: 0) / 100f, c.readiness, Modifier.size(220.dp), stroke = 20.dp, animate = !LocalReduceMotion.current, alpha = if (low) 0.6f else 1f, mark = said?.let { it / 100f }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (score != null) Row { if (low) Text("~", style = AppType.display); CountUp(score, AppType.display) } else Text("—", style = AppType.display)
                            Text("Readiness", style = AppType.subhead, color = c.secondaryLabel)
                        }
                    }
                    if (score != null) Text(
                        coachLine(score, r?.get("contributors").obj()), style = AppType.subhead, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = AppSpace.l),
                    )
                    if (said != null) Text(
                        "You said ${if (words) calmWord("readiness", said) else said}.", style = AppType.subhead, color = c.secondaryLabel,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = AppSpace.xs),
                    )
                }
                ScoreDetails("readiness", "Readiness", c.readiness, d, open == "readiness", medStarted)
            }
        }
        hold?.takeIf { holdGone != day }?.let { h -> item { HoldCard(h) { ctx.holdDismissed = day; holdGone = day } } }
        // Evening verdict: which band the day turned out to be (0 / 1 / 2), asked once.
        if (said != null && guess?.get("verdict").num() == null && LocalTime.now().hour >= 18) item {
            InsetCard {
                Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                    Text("How did today actually go?", style = AppType.headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        listOf("Drained", "OK", "Strong").forEachIndexed { i, label ->
                            Text(
                                label, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.label,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.secondaryLabel.copy(alpha = 0.16f))
                                    .clickable(role = Role.Button) { haptic.tick(); scope.launch { answered(Api.verdict(ctx, day, i), false) } }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            InsetCard { SmallRings(d, open?.takeIf { it == "sleep" || it == "activity" }, { open = it }, medStarted) }
        }
        d["cardio"].obj()?.let { item { CardioLoad(it) } }
        val tonight = d["tonight"].obj()
        val plan = tonight?.get("plan").obj()
        if (LocalTime.now().hour >= 18 && tonight != null && plan != null) item { TonightCard(plan, tonight["bedtime"].obj(), tonight["wind_down_usual"].num(), tonight["what_if"].obj()) }
        else if (LocalTime.now().hour >= 18 && tonight != null) {
            val b = tonight["bedtime"].obj()?.takeIf { it["status"].str() == "ok" }
            val start = b?.get("start_min").num()
            val end = b?.get("end_min").num()
            val debt = tonight["debt"].obj()?.get("debt_min").num()?.roundToInt()
            // start/end are minutes after noon (600 = 22:00).
            val parts = listOfNotNull(
                if (start != null && end != null) "bed ${clock(LocalTime.NOON.plusMinutes(start.toLong()))}–${clock(LocalTime.NOON.plusMinutes(end.toLong()))} suits you" else null,
                debt?.let { if (it < 30) "sleep debt cleared" else "${duration(it)} sleep debt" },
            )
            if (parts.isNotEmpty()) item {
                Text("Tonight: " + parts.joinToString(" · "), style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.xs))
            }
        }
        d["signals"].obj()?.let { item { BodySignals(it, medStarted) } }
        // An event logged today with no night, no earlier episode and no note has nothing to show yet.
        d["last_time"].obj()?.takeIf { (it["nights"].num() ?: 0.0) > 0 || it["before"].obj() != null || it["price"].obj()?.get("status").str() == "ok" || it["note"].str() != null }?.let { lt ->
            item { LastTimeCard(lt) { text -> scope.launch { answered(Api.putNote(ctx, lt["key"].str().orEmpty(), text), false) } } }
        }
        item {
            val v = d["vitals"].obj()
            val tiles = listOf("Heart rate" to ("hr" to "bpm"), "HRV" to ("hrv" to "ms"), "SpO₂ · latest" to ("spo2" to "%"), "Ring battery" to ("battery" to "%"))
            var expanded by remember { mutableStateOf<String?>(null) }
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Vitals", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        pair.forEach { (label, m) ->
                            val x = v?.get(m.first).num()
                            InsetCard(Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.fillMaxSize().clickable { expanded = if (expanded == m.first) null else m.first }.padding(AppSpace.l)) {
                                    Text(label, style = AppType.footnote, color = c.secondaryLabel)
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(x?.roundToInt()?.toString() ?: "—", style = AppType.title)
                                        if (x != null) Text(" ${m.second}", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(bottom = 3.dp))
                                    }
                                    val line = if (m.first == "hr") hr else if (m.first == "hrv") hrv else emptyList()
                                    if (line.size > 1) Sparkline(line, if (m.first == "hr") c.heart else c.hrv, Modifier.padding(top = AppSpace.s).fillMaxWidth().height(40.dp))
                                    AnimatedVisibility(expanded == m.first) {
                                        Text(VITALS_INFO[m.first].orEmpty(), style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
                                    }
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
                            ) { es.forEach { JournalChip(it.label(), it.kind == "workout" && it.note == "auto") } }
                        }
                    }
                    TintButton(if (LocalTime.now().hour < 12) "Log yesterday" else "Log today", Modifier.padding(AppSpace.l)) { sheet = true }
                }
            }
        }
        marks.forEach { t -> item { MarkCard(t, scope, ::loadMarks) } }
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
    }
    if (testing) TestDialog(data?.get("guess").obj()?.get("on").str() == "true", { testing = false }) { kind, metric ->
        testing = false
        scope.launch { Api.startExperiment(ctx, kind, metric)?.let { exp = it["active"].obj() } }
    }
    if (sheet) CheckInSheet(journal, medTime, scope, sheetDay, data, ::loadJournal) { sheet = false; sheetDay = null }
}

/** Sleep + Activity rings; tapping one grows it into the card with its details (shared element), a plain switch under reduce-motion. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SmallRings(d: JsonObject, open: String?, onOpen: (String?) -> Unit, medStarted: String?) {
    // Only the first rings count up; the copies made by expand/collapse start at the score.
    // Saveable: the lazy item is disposed when scrolled away and must not count up from 0 again.
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    if (LocalReduceMotion.current) return SmallRingsContent(d, open, onOpen, medStarted, !shown, null, null)
    SharedTransitionLayout {
        AnimatedContent(
            open, label = "rings",
            transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false) },
        ) { k -> SmallRingsContent(d, k, onOpen, medStarted, !shown, this@SharedTransitionLayout, this) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SmallRingsContent(d: JsonObject, open: String?, onOpen: (String?) -> Unit, medStarted: String?, animate: Boolean, s: SharedTransitionScope?, a: AnimatedVisibilityScope?) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val meta = mapOf("sleep" to ("Sleep" to c.sleep), "activity" to ("Activity" to c.activity))
    @Composable fun ring(key: String, size: Int, stroke: Int, number: TextStyle) {
        val score = d[key].obj()?.get("score").num()?.roundToInt()
        val low = d["confidence"].obj()?.get(key).str() == "low"
        // Only while the expand runs: a lazy-list prefetch measures this item before SharedTransitionLayout is placed,
        // and a sharedElement measured then throws "Uninitialized LayoutCoordinates".
        val m = if (s == null || a == null || a.transition.currentState == a.transition.targetState) Modifier else with(s) { Modifier.sharedElement(rememberSharedContentState("ring-$key"), a) }
        ScoreRing((score ?: 0) / 100f, meta.getValue(key).second, m.size(size.dp), stroke = stroke.dp, animate = animate, alpha = if (low) 0.6f else 1f) {
            if (score != null) Row { if (low) Text("~", style = number); CountUp(score, number, animate = animate) } else Text("—", style = number)
        }
    }
    // Calm mode: word pills; a tap opens the same details, ring and number included.
    if (open == null && d["calm"].str() == "true") FlowRow(
        Modifier.fillMaxWidth().padding(AppSpace.l),
        horizontalArrangement = Arrangement.spacedBy(AppSpace.s, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(AppSpace.s),
    ) {
        meta.forEach { (key, mt) ->
            Text(
                "${mt.first} · ${calmWord(key, d[key].obj()?.get("score").num()?.roundToInt())}",
                style = AppType.subhead.copy(fontWeight = FontWeight.Medium), color = c.label,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(mt.second.copy(alpha = 0.14f))
                    .clickable(role = Role.Button) { haptic.tick(); onOpen(key) }.padding(horizontal = AppSpace.l, vertical = AppSpace.m),
            )
        }
    } else if (open == null) Row(Modifier.padding(horizontal = AppSpace.m, vertical = AppSpace.l)) {
        meta.forEach { (key, mt) ->
            Column(
                Modifier.weight(1f).clip(AppShape.small).clickable { onOpen(key) }.padding(AppSpace.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ring(key, 96, 10, AppType.title)
                Text(mt.first, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
            }
        }
    } else Column {
        Column(
            Modifier.fillMaxWidth().clickable { onOpen(null) }.padding(AppSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ring(open, 168, 16, AppType.display)
            Text(meta.getValue(open).first, style = AppType.headline, modifier = Modifier.padding(top = AppSpace.m))
        }
        val slide = if (a == null) Modifier else with(a) {
            Modifier.animateEnterExit(slideInVertically(tween(320, delayMillis = 120)) { it / 4 } + fadeIn(tween(320, delayMillis = 120)), fadeOut(tween(90)))
        }
        Box(slide) { ScoreDetails(open, meta.getValue(open).first, meta.getValue(open).second, d, true, medStarted) }
    }
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
                // Days scored before the backend sent confidence have none: no row.
                val level = d["confidence"].obj()?.get(key).str()
                val bars = listOf("low", "medium", "high").indexOf(level) + 1
                if (bars > 0) Row(Modifier.padding(top = AppSpace.s), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp, 14.dp).semantics { contentDescription = "Confidence: $level." }) {
                        val w = size.width / 5
                        repeat(3) { i ->
                            val h = size.height * (i + 1) / 3
                            drawRoundRect(color.copy(alpha = if (i < bars) 1f else 0.2f), Offset(i * 2 * w, size.height - h), Size(w, h), CornerRadius(w / 2))
                        }
                    }
                    val worn = d["wear"].obj()?.get("worn_hours").num()
                    val awake = d["wear"].obj()?.get("awake_hours").num()
                    // The wear sentence only when it agrees with the level (0.8 as backend scoring.confidence); a missing contributor can lower it too.
                    val what = if (key == "activity" && worn != null && awake != null && awake > 0 && (worn / awake >= 0.8) == (level == "high"))
                        "Ring worn ${worn.roundToInt()} of ${awake.roundToInt()} waking hours"
                    else if (level == "high") "The ring data behind this score is nearly complete" else "Some of the ring data behind this score is missing"
                    Text(
                        "$what, so this is a ${listOf("rough", "fair", "solid")[bars - 1]} estimate.",
                        style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.s),
                    )
                }
            }
            (d[key].obj()?.get("contributors").obj() ?: JsonObject(emptyMap())).forEach { (n, v) ->
                Hairline(Modifier.padding(start = AppSpace.l))
                Contributor(NAMES[n] ?: n, v.num(), color, CONTRIBUTOR_INFO[n])
            }
            val g = d["guess"].obj()?.takeIf { key == "readiness" && it["on"].str() == "true" }
            if (g != null) {
                Hairline(Modifier.padding(start = AppSpace.l))
                Column(Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m), verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                    Text("Your guesses", style = AppType.headline)
                    val st = g["stats"].obj()
                    val n = st?.get("n").num()?.roundToInt() ?: 0
                    val off = st?.get("off").num()?.roundToInt()
                    if (st?.get("status").str() != "ok" || off == null) Text("Guess on 5 mornings to see how close you get. $n so far.", style = AppType.subhead, color = c.secondaryLabel)
                    else {
                        Text("Usually within $off ${if (off == 1) "point" else "points"} of the ring, over $n mornings.", style = AppType.subhead)
                        val bias = st?.get("bias").num() ?: 0.0
                        if (kotlin.math.abs(bias) >= 3) Text(
                            "You tend to feel ${if (bias > 0) "better" else "worse"} than the ring says, by about ${kotlin.math.abs(bias).roundToInt()} points.", style = AppType.subhead,
                        )
                        val split = st?.get("split").num()?.roundToInt() ?: 0
                        if (split >= 3) Text(
                            "When you and the ring disagreed, your read fit the day ${st?.get("you").num()?.roundToInt() ?: 0} of $split times, the ring's ${st?.get("ring").num()?.roundToInt() ?: 0}.",
                            style = AppType.subhead,
                        )
                    }
                    val days = (g["days"] as? JsonArray)?.mapNotNull { p -> (p as? JsonArray)?.let { (it.getOrNull(0).num() ?: return@let null) to (it.getOrNull(1).num() ?: return@let null) } }.orEmpty()
                    if (days.isNotEmpty()) Dumbbells(days)
                }
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

/** "Dumbbells": up to 14 mornings, oldest first; hollow dot = your guess, filled dot = the ring's score, a thin line between. */
@Composable
private fun Dumbbells(days: List<Pair<Double, Double>>) {
    val c = AppTheme.colors
    val (g, r) = days.last()
    Canvas(
        Modifier.padding(top = AppSpace.s).fillMaxWidth().height(64.dp).semantics {
            contentDescription = "Your guess against the ring on the last ${days.size} guessed mornings. Latest: you said ${g.roundToInt()}, the ring ${r.roundToInt()}."
        },
    ) {
        val slot = size.width / 14
        val dot = minOf(slot * 0.3f, 4.dp.toPx())
        val w = 1.5.dp.toPx()
        // The days' own range, at least 30 points tall so a small gap is not blown up.
        val all = days.flatMap { listOf(it.first, it.second) }
        val mid = (all.max() + all.min()) / 2
        val span = maxOf(all.max() - all.min(), 30.0)
        fun y(v: Double) = size.height / 2 - ((v - mid) / span).toFloat() * (size.height - 2 * dot - w)
        days.forEachIndexed { i, (guess, score) ->
            val x = slot * (i + 0.5f)
            drawLine(c.secondaryLabel, Offset(x, y(guess)), Offset(x, y(score)), w)
            drawCircle(c.readiness, dot, Offset(x, y(score)))
            drawCircle(c.card, dot, Offset(x, y(guess)))
            drawCircle(c.secondaryLabel, dot - w / 2, Offset(x, y(guess)), style = Stroke(w))
        }
    }
}

/** The gate of Guess first: shown instead of the scores until the morning's guess (or "Just show me" = null) is in. */
@Composable
private fun GuessCard(calm: Boolean, onGuess: (Int?) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var v by rememberSaveable { mutableStateOf<Int?>(null) }
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(AppSpace.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("How recovered do you feel?", style = AppType.title, textAlign = TextAlign.Center)
            GuessDial(v, calm, Modifier.padding(top = AppSpace.l)) { v = it }
            // TintButton has no disabled state: faded and inert until a dot is placed.
            TintButton("Lock it in", Modifier.padding(top = AppSpace.xl).alpha(if (v == null) 0.4f else 1f).semantics { if (v == null) disabled() }) {
                v?.let { haptic.tick(); onGuess(it) }
            }
            Text(
                "Just show me", style = AppType.subhead, color = c.tint,
                modifier = Modifier.padding(top = AppSpace.s).clip(AppShape.small).clickable(role = Role.Button) { haptic.tick(); onGuess(null) }.padding(AppSpace.m),
            )
        }
    }
}

/** "Your dot": the readiness ring's empty track; a tap or drag on the ring places the guess, 0..100 clockwise from 12 o'clock as ScoreRing sweeps. "?" and no dot until the first touch: a preset position would anchor the guess. */
@Composable
private fun GuessDial(value: Int?, calm: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val now by rememberUpdatedState(value)
    fun set(v: Int) {
        if (v == now) return
        if (v / 5 != now?.div(5)) haptic.tick()
        onChange(v)
    }
    val word = value?.let { calmWord("readiness", it) }
    Box(
        modifier.size(220.dp).pointerInput(Unit) {
            // null in the middle of the dial, where the angle means nothing.
            fun at(p: Offset): Int? {
                val x = p.x - size.width / 2f
                val y = size.height / 2f - p.y
                if (kotlin.math.hypot(x, y) < size.width / 4f) return null
                return ((Math.toDegrees(kotlin.math.atan2(x, y).toDouble()) + 360) % 360 / 3.6).roundToInt()
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                set(at(down.position) ?: return@awaitEachGesture) // a touch in the middle still scrolls the list
                drag(down.id) { ch ->
                    ch.consume() // keeps the list from scrolling under the finger
                    val cur = now ?: 0
                    // 0 and 100 share 12 o'clock: a drag stops there instead of jumping to the other end.
                    at(ch.position)?.let { set(if (cur > 75 && it < 25) 100 else if (cur < 25 && it > 75) 0 else it) }
                }
            }
        }.clearAndSetSemantics {
            contentDescription = "How recovered you feel, 0 to 100"
            stateDescription = if (value == null) "Not set" else if (calm) word.orEmpty() else "$value, $word"
            progressBarRangeInfo = ProgressBarRangeInfo((value ?: 0).toFloat(), 0f..100f)
            setProgress { set(it.roundToInt().coerceIn(0, 100)); true }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 20.dp.toPx()
            val topLeft = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(c.readiness.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arc, style = Stroke(w))
            if (value != null) {
                // No good or bad colour before the reveal.
                drawArc(c.secondaryLabel, -90f, 3.6f * value, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
                val a = Math.toRadians(3.6 * value)
                val o = center + Offset(kotlin.math.sin(a).toFloat(), -kotlin.math.cos(a).toFloat()) * (arc.width / 2)
                drawCircle(c.card, w * 0.36f, o)
                drawCircle(c.secondaryLabel, w * 0.29f, o, style = Stroke(w * 0.14f))
            }
        }
        if (value == null) Text("?", style = AppType.display, color = c.secondaryLabel)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Calm mode: the word only.
            if (calm) Text(word.orEmpty(), style = AppType.largeTitle, textAlign = TextAlign.Center, modifier = Modifier.width(150.dp))
            else {
                Text("$value", style = AppType.display)
                Text(word.orEmpty(), style = AppType.subhead, color = c.secondaryLabel)
            }
        }
    }
}

// Calm-mode words per score: 85 or more, 70–84, below 70 (readiness as backend notify's band words).
private val CALM = mapOf(
    "readiness" to listOf("Ready", "Steady", "Take it easy"), "sleep" to listOf("Solid", "Fair", "Light"),
    "activity" to listOf("Active", "Moderate", "Light"),
)

/** The word shown instead of a score in calm mode (Today and the widget); "—" without a score. */
internal fun calmWord(key: String, score: Int?): String =
    if (score == null) "—" else CALM.getValue(key)[if (score >= 85) 0 else if (score >= 70) 1 else 2]

/** Calm hero in the ring's 220 dp footprint: the open Circa mark (thin tail round to a thick head, dot in the gap) breathing as the Breathe orb idles, [word] instead of a number. */
@Composable
private fun CalmArc(word: String, alpha: Float) {
    val c = AppTheme.colors
    val breath = if (LocalReduceMotion.current) null
        else rememberInfiniteTransition(label = "calm").animateFloat(0.97f, 1f, infiniteRepeatable(tween(4000, easing = EaseInOut), RepeatMode.Reverse), label = "calm")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer { val k = breath?.value ?: 1f; scaleX = k; scaleY = k; this.alpha = alpha }
                .semantics { contentDescription = "Readiness: $word." },
        ) {
            val head = 20.dp.toPx()
            val tail = head * 4 / 9
            val r = (size.minDimension - head) / 2
            val box = Size(2 * r, 2 * r)
            // As the launcher mark (0° = 3 o'clock, clockwise): tail at 334.6°, head at 296°, dot at 316.4°.
            // Short round-capped pieces of growing width make the taper.
            repeat(90) { i ->
                val t = i / 89f
                drawArc(lerp(c.readiness, c.readinessLight, t), 334.6f + 321.4f * i / 90, 321.4f / 90, false, center - Offset(r, r), box, style = Stroke(tail + (head - tail) * t, cap = StrokeCap.Round))
            }
            drawCircle(c.readinessLight, head / 3, center + Offset(r * 0.724f, r * -0.69f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(word, style = AppType.largeTitle, textAlign = TextAlign.Center, modifier = Modifier.width(150.dp))
            Text("Readiness", style = AppType.subhead, color = c.secondaryLabel)
        }
    }
}

private val ZONES = listOf("Easy", "Light", "Moderate", "Hard", "Max")
private val COACH = mapOf(
    "recovered" to "Recovered — room for a bigger day.",
    "steady" to "Steady — a normal day keeps you balanced.",
    "easy" to "Take it easy — recovery first.",
)

/** `/today.cardio`: load against today's target, zone minutes; tap → five zone rows. */
@Composable
private fun CardioLoad(cd: JsonObject) {
    val c = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val status = cd["status"].str()
    val load = cd["load"].num()
    val t = cd["target"].obj()
    val lo = t?.get("lo").num()
    val hi = t?.get("hi").num()
    val minutes = (cd["minutes"] as? JsonArray)?.map { it.num()?.roundToInt() ?: 0 }
    InsetCard {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = status != "needs_age" && minutes != null) { open = !open }.padding(AppSpace.l),
            verticalArrangement = Arrangement.spacedBy(AppSpace.s),
        ) {
            Text("Cardio load", style = AppType.footnote, color = c.secondaryLabel)
            if (status == "needs_age") {
                Text("Set your birth year in Settings to get heart-rate zones.", style = AppType.footnote, color = c.secondaryLabel)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
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
                COACH[t?.get("band").str()]?.let { Text(it + if (now > hi) " You're past today's target." else "", style = AppType.subhead) }
            }
            if (minutes != null) Text("${minutes.sum()} min in heart-rate zones", style = AppType.footnote, color = c.secondaryLabel)
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

/** Trend line (24 h HR, 14-day HRV) scaled to its own min..max. */
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
    fun label() = (KIND_LABEL[kind] ?: kind) + if (kind == "bp") " ${value.orEmpty()}" else if (kind == "medication" || kind == "breathe") " ${clock(value.orEmpty())}" else ""
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

private fun parseJournal(json: JsonElement?): List<Entry> = (json.obj()?.get("entries") as? JsonArray)?.mapNotNull { it.obj() }?.filter { it["kind"].str() != "phone_in_bed" }?.mapNotNull { // auto row, not for the day list
    Entry(it["id"].str()?.toIntOrNull() ?: return@mapNotNull null, it["date"].str().orEmpty(), it["kind"].str().orEmpty(), it["value"].str(), it["note"].str())
} ?: emptyList()

/** Short names for chips and the Trends summary line. */
internal val KIND_LABEL = mapOf(
    "alcohol" to "Alcohol", "caffeine_late" to "Late caffeine", "late_meal" to "Late meal", "workout" to "Workout",
    "sick" to "Sick", "travel" to "Travel", "stress" to "Stress", "medication" to "Medication", "bp" to "BP", "note" to "Note",
    "breathe" to "Breathe",
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
    "breathe" to "Sessions log themselves; we compare the hour and the night after with the rest.",
)

@Composable
private fun JournalChip(text: String, auto: Boolean = false) {
    val c = AppTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.secondaryLabel.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpace.xs), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.label)
        if (auto) Text("auto", style = AppType.tab, color = c.secondaryLabel)
    }
}

/** Suggested entry: a dashed outline that fills solid once logged (one 600 ms ease, a plain switch under reduce-motion). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestChip(label: String, logged: Boolean, hint: String = "Suggested, tap to log", onLong: (() -> Unit)? = null, onLog: () -> Unit) {
    val c = AppTheme.colors
    // Local state flips at once; the server state re-keys it after the reload.
    var on by remember(logged) { mutableStateOf(logged) }
    val fill by animateFloatAsState(if (on) 1f else 0f, if (LocalReduceMotion.current) snap() else tween(600), label = "suggest")
    val onTint = if (c.background.luminance() < 0.5f) c.label else c.card // white in both themes
    Box(Modifier.clip(RoundedCornerShape(50)).combinedClickable(enabled = !on, role = Role.Button, onLongClick = onLong) { on = true; onLog() }) {
        Canvas(Modifier.matchParentSize().semantics { contentDescription = if (on) "Logged" else hint }) {
            val w = 1.5.dp.toPx()
            val round = CornerRadius(size.height / 2)
            drawRoundRect(c.tint.copy(alpha = fill), cornerRadius = round)
            drawRoundRect(
                c.secondaryLabel.copy(alpha = 1 - fill), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), round,
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        Text(
            label, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = lerp(c.label, onTint, fill),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** Writes go to [scope] (outlives the sheet) so a pending note or BP is saved when the sheet closes. [today] is the `/today` body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckInSheet(entries: List<Entry>, medTime: String, scope: CoroutineScope, startDay: Int?, today: JsonObject?, reload: suspend () -> Unit, onDismiss: () -> Unit) {
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
            val date = LocalDate.now().minusDays(1L - day)
            // `/today.suggest` belongs to the wake date; what it suggests happened the evening before.
            val suggest = today?.get("suggest").obj()?.takeIf { today?.get("date").str() ==date.plusDays(1).toString() }
            key(date) { DayForm(date.toString(), entries.filter { it.date == date.toString() }, medTime, scope, suggest, reload) }
        }
    }
}

private fun bpOk(s: String): Boolean {
    val n = s.split('/').map { it.toIntOrNull() ?: return false }
    return n.size == 2 && n[0] in 70..250 && n[1] in 40..150
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DayForm(date: String, es: List<Entry>, medTime: String, scope: CoroutineScope, suggest: JsonObject?, reload: suspend () -> Unit) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    fun run(block: suspend () -> Unit) { scope.launch { block(); reload() } }

    // Suggested kinds not yet logged when the form opens; a tapped chip stays, filled, until the sheet closes.
    val offered = remember(suggest) { (suggest?.get("kinds") as? JsonArray)?.mapNotNull { it.str() }?.filter { k -> es.none { it.kind == k } }.orEmpty() }
    val delta = suggest?.get("delta").num()?.roundToInt()
    var dismissed by remember { mutableStateOf(ctx.suggestDismissed == date) }
    if (delta != null && offered.isNotEmpty() && !dismissed) InsetCard {
        Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
            Text("Evening heart rate was +$delta bpm. Any of these?", style = AppType.subhead)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                offered.forEach { kind ->
                    SuggestChip(KIND_LABEL[kind] ?: kind, es.any { it.kind == kind }) { haptic.tick(); run { Api.addJournal(ctx, date, kind) } }
                }
                Text(
                    "Neither", style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.tint,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { haptic.tick(); ctx.suggestDismissed = date; dismissed = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }

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
                    clock(d.value.orEmpty()), style = AppType.body, color = c.tint,
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

/** Tonight's bedtime plan (backend `tonight.plan`; times are minutes after noon). Tap opens the Sleep tab. */
@Composable
private fun TonightCard(p: JsonObject, bedtime: JsonObject?, settle: Double?, whatIf: JsonObject?) {
    val c = AppTheme.colors
    val ctx = LocalContext.current
    fun noon(m: Double?) = clock(LocalTime.NOON.plusMinutes((m ?: 0.0).roundToInt().toLong()))
    val extra = p["extra_min"].num()?.roundToInt() ?: 0
    val base = duration((p["sleep_min"].num()?.roundToInt() ?: 0) - extra)
    val alarm = noon(p["wake_min"].num())
    val foot = when (p["window"].str()) {
        "later" -> bedtime?.get("start_min").num()?.let { "Your best nights start around ${noon(it)} — a little earlier tonight clears debt." }
        "earlier" -> "Later than your best window; tonight's debt share keeps it short."
        else -> null
    }
    InsetCard {
        Column(
            Modifier.fillMaxWidth().clickable {
                // Same route as the notification deep link; MainActivity is singleTop.
                ctx.startActivity(android.content.Intent(ctx, dev.ring.health.MainActivity::class.java).putExtra("open", "sleep"))
            }.padding(AppSpace.l),
        ) {
            Text("Tonight", style = AppType.subhead, color = c.sleep)
            Text("In bed by ${noon(p["in_bed_by_min"].num())}", style = AppType.title, modifier = Modifier.padding(top = AppSpace.xs))
            Text(
                if (extra > 0) "$base + ${duration(extra)} catch-up before your $alarm alarm" else "$base before your $alarm alarm",
                style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
            )
            if (settle != null) Text("Your body usually starts to settle around ${noon(settle)}.", style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.s))
            // What-if (backend `tonight.what_if`): next-day readiness after rest / hard days in the user's own history.
            val rest = whatIf?.get("rest").obj()
            val train = whatIf?.get("train").obj()
            val rf = rest?.get("full").obj()
            val tf = train?.get("full").obj()
            if (rf != null && tf != null) {
                Row(Modifier.fillMaxWidth().padding(top = AppSpace.l)) {
                    WhatIfRing("Rest", rf, Modifier.weight(1f))
                    WhatIfRing("Train", tf, Modifier.weight(1f))
                }
                Text(
                    "Tomorrow's readiness after ${rf["n"].num()?.roundToInt()} rest days and ${tf["n"].num()?.roundToInt()} hard days like this.",
                    style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s),
                )
                val short = listOfNotNull(
                    rest?.get("short").obj()?.get("median").num()?.let { "${it.roundToInt()} on rest days" },
                    train?.get("short").obj()?.get("median").num()?.let { "${it.roundToInt()} on hard days" },
                )
                if (short.isNotEmpty()) Text("After a short night it is usually ${join(short)}.", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs))
            }
            if (foot != null) Text(foot, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
        }
    }
}

/** One what-if bucket (`{median, lo, hi, n}`): the median as a ring, the usual range as a faint outer arc. */
@Composable
private fun WhatIfRing(name: String, b: JsonObject, modifier: Modifier) {
    val c = AppTheme.colors
    val median = b["median"].num()?.roundToInt() ?: 0
    val lo = b["lo"].num()?.roundToInt() ?: median
    val hi = b["hi"].num()?.roundToInt() ?: median
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "$name: readiness $median, usually $lo to $hi." }) {
                val w = 3.dp.toPx()
                drawArc(c.readinessLight, -90f + 3.6f * lo, (3.6f * (hi - lo)).coerceAtLeast(1f), false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            ScoreRing(median / 100f, c.readiness, Modifier.size(60.dp), stroke = 6.dp, animate = !LocalReduceMotion.current) { CountUp(median, AppType.title) }
        }
        Text(name, style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.xs))
        Text("usually $lo–$hi", style = AppType.footnote, color = c.secondaryLabel)
    }
}

/** Time picker dialog in the device's 12/24 h format; [onPick] gets "HH:MM". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickDialog(initial: String, onDismiss: () -> Unit, onTurnOff: (() -> Unit)? = null, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    val t = runCatching { LocalTime.parse(initial) }.getOrDefault(LocalTime.of(8, 0))
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    val knob = if (c.background.luminance() < 0.5f) c.label else c.card
    val faint = c.secondaryLabel.copy(alpha = 0.16f)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        confirmButton = { TextButton({ onPick("%02d:%02d".format(Locale.ROOT, state.hour, state.minute)) }) { Text("Done", color = c.tint) } },
        dismissButton = {
            Row {
                if (onTurnOff != null) TextButton(onTurnOff) { Text("Turn off", color = c.tint) }
                TextButton(onDismiss) { Text("Cancel", color = c.tint) }
            }
        },
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
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(if (i <= level) col else c.emptySegment))
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

/** "1 night", "2 nights", "1.5 nights" (a median). */
private fun nightsText(v: Double) = if (v == 1.0) "1 night" else "${if (v % 1 == 0.0) v.roundToInt().toString() else v.toString()} nights"

/** `/today.last_time`: how long the raised nights after an event (or a run with no event) lasted before, plus the user's note to themselves. */
@Composable
private fun LastTimeCard(lt: JsonObject, onNote: (String) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    val why = lt["why"].str()
    val n = lt["nights"].num()?.roundToInt() ?: 0
    val before = lt["before"].obj()
    val was = before?.get("nights").num()?.roundToInt()
    val first = runCatching { LocalDate.parse(before?.get("first").str()) }.getOrNull()
    val usual = lt["price"].obj()?.takeIf { it["status"].str() == "ok" }?.get("nights").num()
    val expect = if (why == "closed") 0 else kotlin.math.ceil(usual ?: was?.toDouble() ?: 0.0).toInt()
    val after = "After " + join((lt["kinds"] as? JsonArray)?.mapNotNull { LOGGED[it.str()] }.orEmpty().ifEmpty { listOf("what you logged") })
    val dm = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val note = lt["note"].str()
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("Last time", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            Column(Modifier.fillMaxWidth().padding(start = AppSpace.l, top = AppSpace.l, end = AppSpace.l, bottom = AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                if (n > 0 || expect > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    WalkDown(
                        n, expect, ring = why != "signals", closed = why == "closed", modifier = Modifier.weight(1f),
                        describe = when (why) {
                            "closed" -> "Signals raised for ${nightsText(n.toDouble())} after the event, then back to calm."
                            "event" -> "${nightsText(n.toDouble())} raised since the event." + if (expect > 0) " It usually takes ${nightsText(expect.toDouble())}." else ""
                            else -> "${nightsText(n.toDouble())} raised in a row." + if (expect > 0) " Last time it lasted ${nightsText(expect.toDouble())}." else ""
                        },
                    )
                    if (why == "closed") Text(nightsText(n.toDouble()), style = AppType.headline, modifier = Modifier.padding(start = AppSpace.m))
                }
                when (why) {
                    "event" -> {
                        Text(after, style = AppType.headline)
                        if (n > 0) Text("Night $n: ${if (n > 1) "still " else ""}above your usual.", style = AppType.subhead)
                        // The first night's flagged inputs.
                        val usualBy = lt["usual"].obj()
                        lt["deltas"].obj()?.mapNotNull { (k, v) -> flagLine(k, v.num(), usualBy?.get(k).num()) }?.forEach { Text(it, style = AppType.subhead) }
                        if (first != null && was != null) Text(
                            "Last time (${first.format(dm)}): " + if (was == 0) "no raised nights." else "back to usual after ${nightsText(was.toDouble())}.", style = AppType.subhead,
                        )
                        if (usual != null) Text(if (usual == 0.0) "Usually no extra nights for you." else "Usually ${nightsText(usual)} for you.", style = AppType.subhead, color = c.secondaryLabel)
                    }
                    "closed" -> {
                        Text("Back in your usual range.", style = AppType.headline)
                        val on = runCatching { " on " + LocalDate.parse(lt["last"].str()).format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) }.getOrDefault("")
                        Text("$after$on: ${nightsText(n.toDouble())}.", style = AppType.subhead)
                    }
                    else -> {
                        Text("This has happened before.", style = AppType.headline)
                        if (first != null && was != null) {
                            val end = first.plusDays(was - 1L)
                            val range = first.format(if (first.month == end.month) DateTimeFormatter.ofPattern("d") else dm) + " to " + end.format(dm)
                            Text("Last time your signals ran high ($range), it lasted ${nightsText(was.toDouble())}.", style = AppType.subhead)
                        }
                    }
                }
                if (note != null) {
                    Text(note, style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.xs))
                    Text("You wrote this last time.", style = AppType.footnote, color = c.secondaryLabel)
                }
                Text(
                    if (note == null) "Leave a note for next time" else "Edit note", style = AppType.body, color = c.tint,
                    modifier = Modifier.clip(AppShape.small).clickable(role = Role.Button) { haptic.tick(); editing = true }.padding(vertical = AppSpace.s),
                )
            }
        }
    }
    if (editing) NoteDialog(note, { editing = false }, onNote)
}

/** The 280-character note dialog; [onNote] gets the trimmed text (empty removes the note). */
@Composable
private fun NoteDialog(note: String?, onDismiss: () -> Unit, onNote: (String) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    // Cursor after the existing text, so an edit continues it.
    var field by remember { mutableStateOf(TextFieldValue(note.orEmpty(), TextRange(note.orEmpty().length))) }
    val text = field.text
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
        title = { Text("Note for next time", style = AppType.title) },
        confirmButton = { TextButton({ haptic.tick(); onDismiss(); onNote(text.trim()) }) { Text("Save", color = c.tint) } },
        dismissButton = { TextButton({ haptic.tick(); onDismiss() }) { Text("Cancel", color = c.tint) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                Box(Modifier.fillMaxWidth().clip(AppShape.small).background(c.secondaryLabel.copy(alpha = 0.12f)).padding(AppSpace.m)) {
                    if (text.isEmpty()) Text("What helped, what you would do differently", style = AppType.body, color = c.secondaryLabel)
                    BasicTextField(
                        field, { field = if (it.text.length > 280) it.copy(text = it.text.take(280)) else it }, textStyle = AppType.body.copy(color = c.label),
                        cursorBrush = SolidColor(c.tint), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                }
                Text("${text.length}/280", style = AppType.footnote, color = c.secondaryLabel)
            }
        },
    )
}

private val FEELINGS = listOf("tired", "flat", "wired", "good")

/** Tap: now. Long press: an earlier time today. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPill(label: String, onLong: () -> Unit, onTap: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    Text(
        label, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.label,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.secondaryLabel.copy(alpha = 0.16f))
            .combinedClickable(role = Role.Button, onLongClickLabel = "Pick an earlier time", onLongClick = { haptic.tick(); onLong() }) { haptic.tick(); onTap() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/**
 * Right now: four feelings and four intakes. A feeling or intake is the sample `moment_<kind>` (stored first, pushed once,
 * Undo = value 2 at the same second); Dose is a medication journal row. [onFeeling] gets the feeling just tapped, null when undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RightNow(day: String, scope: CoroutineScope, onFeeling: (String?) -> Unit, reload: suspend () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    var just by remember { mutableStateOf<Triple<String, Long, Int?>?>(null) } // kind, epoch ms, journal id (dose) that Undo takes back
    var gone by remember { mutableStateOf(emptySet<String>()) }
    var pick by remember { mutableStateOf<String?>(null) }
    fun put(kind: String, at: Long) {
        if (kind == "dose") {
            scope.launch {
                val hhmm = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                val id = Api.addJournal(ctx, day, "medication", hhmm)
                if (id == null) Toast.makeText(ctx, "Not saved. Try again.", Toast.LENGTH_SHORT).show() else { just = Triple(kind, at, id); reload() }
            }
            return
        }
        var ts = at
        while ("$kind@$ts" in gone) ts += 1000 // an undone second stays undone (the larger value wins): the new moment moves one on
        just = Triple(kind, ts, null)
        if (kind in FEELINGS) onFeeling(kind)
        scope.launch { mark(ctx, "moment_$kind", ts, false); reload() }
    }
    fun undo(j: Triple<String, Long, Int?>) {
        just = null
        val id = j.third
        if (id != null) { scope.launch { Api.deleteJournal(ctx, id); reload() }; return }
        gone = gone + "${j.first}@${j.second}"
        if (j.first in FEELINGS) onFeeling(null)
        scope.launch { mark(ctx, "moment_${j.first}", j.second, true); reload() }
    }
    LaunchedEffect(just) { if (just != null) { delay(10_000); just = null } }
    val pills = @Composable { kinds: List<String> ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            kinds.forEach { k -> NowPill(k.replaceFirstChar { it.uppercase() }, { pick = k }) { put(k, System.currentTimeMillis() / 1000 * 1000) } }
        }
    }
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            Text("How are you right now?", style = AppType.headline)
            pills(FEELINGS)
            Text("Just had", style = AppType.headline, modifier = Modifier.padding(top = AppSpace.xs))
            pills(listOf("coffee", "alcohol", "meal", "dose"))
            just?.let { j ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${j.first.replaceFirstChar { it.uppercase() }} · ${clock(j.second / 1000.0)}", style = AppType.subhead, color = c.secondaryLabel)
                    Text(
                        "Undo", style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.tint,
                        modifier = Modifier.padding(start = AppSpace.xs).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { haptic.tick(); undo(j) }.padding(horizontal = AppSpace.m, vertical = 9.dp),
                    )
                }
            }
        }
    }
    pick?.let { k ->
        TimePickDialog(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), { pick = null }) { hhmm ->
            pick = null
            val ts = LocalDate.now().atTime(LocalTime.parse(hhmm)).atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
            if (ts > System.currentTimeMillis()) return@TimePickDialog Toast.makeText(ctx, "Pick a time earlier today.", Toast.LENGTH_SHORT).show()
            put(k, ts)
        }
    }
}

/** After a feeling tap: what the ring and the log say right now (`/today.drive`, `/today.now`), your own note for this feeling, Breathe. */
@Composable
private fun NowCard(feeling: String, d: JsonObject, onNote: (String) -> Unit, onBreathe: () -> Unit, onClose: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    val drive = d["drive"].obj()
    val now = d["now"].obj()
    val note = now?.get("notes").obj()?.get(feeling).str()
    val awakeH = drive?.let { ((it["as_of"].num() ?: return@let null) - (it["wake"].num() ?: return@let null)) / 3600 }?.toInt()?.takeIf { it >= 1 }
    val facts = listOfNotNull(
        drive?.get("sleep_min").num()?.roundToInt()?.takeIf { it > 0 }?.let { "${duration(it)} of sleep" },
        awakeH?.let { "awake $it h" },
        now?.get("dose").str()?.let { "dose at ${clock(it)}" + (now?.get("dose_end").str()?.let { e -> ", active until about ${clock(e)}" } ?: "") },
        now?.get("still_h").num()?.roundToInt()?.takeIf { it >= 1 }?.let { "no walk for $it h" },
    ).joinToString(" · ")
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            if (facts.isNotEmpty()) Text(facts, style = AppType.subhead, color = c.secondaryLabel)
            Text(if (note != null) "Last time you wrote: $note" else "What would help right now? Leave yourself a note for next time.", style = AppType.subhead)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (note == null) "Leave a note" else "Edit note", style = AppType.body, color = c.tint,
                    modifier = Modifier.clip(AppShape.small).clickable(role = Role.Button) { haptic.tick(); editing = true }.padding(vertical = AppSpace.s),
                )
                Text(
                    "Close", style = AppType.body, color = c.tint,
                    modifier = Modifier.clip(AppShape.small).clickable(role = Role.Button) { haptic.tick(); onClose() }.padding(vertical = AppSpace.s),
                )
            }
            TintButton("Breathe") { haptic.tick(); onBreathe() }
        }
    }
    if (editing) NoteDialog(note, { editing = false }, onNote)
}

/** Hold my place: where in last night's podcast or audiobook sleep began. "Jump there" only while the player still holds that item. */
@Composable
private fun HoldCard(h: Hold, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val app = remember(h.pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(h.pkg, 0)).toString() }.getOrNull() }
    val launch = remember(h.pkg) { pm.getLaunchIntentForPackage(h.pkg) }
    val live = PlaybackListener.controllers.firstOrNull { it.packageName == h.pkg && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == h.title }
    val where = if (h.at < 60_000) "right at the start" else "about ${duration((h.at / 60_000).toInt())} in"
    val ranOn = (h.ranOn / 60_000).toInt()
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(start = AppSpace.l, bottom = AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tape(h, "A ${duration((h.duration / 60_000).toInt())} item: you fell asleep $where." + if (ranOn > 0) " It played on for ${duration(ranOn)}." else "", Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { haptic.tick(); onDismiss() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Dismiss", tint = c.secondaryLabel, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.padding(end = AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                if (h.title.isNotEmpty()) Text(h.title, style = AppType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("You fell asleep $where.", style = AppType.subhead)
                if (ranOn > 0) Text("It played on for ${duration(ranOn)}.", style = AppType.footnote, color = c.secondaryLabel)
                if (live != null) TintButton("Jump there", Modifier.padding(top = AppSpace.xs)) { haptic.tick(); live.transportControls.seekTo(h.at) }
                if (launch != null) TintButton("Open ${app ?: "player"}", Modifier.padding(top = AppSpace.xs)) { haptic.tick(); runCatching { ctx.startActivity(launch) } }
            }
        }
    }
}

/** The tape: the item as a bar, filled to the bookmark notch; the stretch that played after sleep began is hatched. */
@Composable
private fun Tape(h: Hold, describe: String, modifier: Modifier) {
    val c = AppTheme.colors
    val reduce = LocalReduceMotion.current
    val run = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(run) { run.animateTo(1f, tween(600)) }
    Canvas(modifier.height(28.dp).semantics { contentDescription = describe }) {
        val bar = 10.dp.toPx()
        val top = (size.height - bar) / 2
        val notch = size.width * h.at / h.duration * run.value
        val until = size.width * h.until / h.duration
        clipPath(Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + bar, CornerRadius(bar / 2))) }) {
            drawRect(c.emptySegment)
            drawRect(c.tint, size = Size(notch, size.height))
            clipRect(left = notch, right = maxOf(until, notch)) {
                val step = 5.dp.toPx()
                var x = notch - bar
                while (x < until) { drawLine(c.secondaryLabel, Offset(x, top + bar), Offset(x + bar, top), 1.5.dp.toPx()); x += step }
            }
        }
        drawLine(c.tint, Offset(notch, 2.dp.toPx()), Offset(notch, size.height - 2.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    }
}

/** The mirror of [LeadStrip]: the event as a hollow ring, then one dot per raised night walking down; faint dots = the nights
 *  it took before ([usual]); [closed] lands on a calm dot and ends with a dashed tear line. */
@Composable
private fun WalkDown(nights: Int, usual: Int, ring: Boolean, closed: Boolean, describe: String, modifier: Modifier) {
    val c = AppTheme.colors
    val reduce = LocalReduceMotion.current
    val fall = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(fall) { fall.animateTo(1f, tween(600)) }
    Canvas(modifier.height(36.dp).semantics { contentDescription = describe }) {
        val slot = size.width / 8
        val r = minOf(slot * 0.3f, 5.dp.toPx())
        val top = r * 1.4f
        val base = size.height - r * 1.4f
        val total = maxOf(nights, usual).coerceIn(1, 6) // raised nights the 8 slots have room for, between the event and calm
        for (i in 0..total + 1) {
            val o = Offset(slot * (i + 0.5f), top + (base - top) * fall.value * i / (total + 1))
            when {
                i == 0 -> if (ring) drawCircle(c.secondaryLabel, r * 1.2f, o, style = Stroke(r * 0.4f))
                i <= nights -> drawCircle(if (i == 1) c.signalHigh else c.signalElevated, r, o)
                closed -> drawCircle(c.signalCalm, r, o)
                i <= usual + 1 -> drawCircle(c.secondaryLabel.copy(alpha = 0.35f), r * 0.7f, o)
            }
        }
        if (closed) drawLine(
            c.secondaryLabel, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
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

private val AFFECT_KINDS = listOf("alcohol", "caffeine_late", "late_meal", "workout", "stress", "travel", "sick", "breathe")

private val AFTER = mapOf(
    "alcohol" to "after alcohol", "caffeine_late" to "after late caffeine", "late_meal" to "after a late meal",
    "workout" to "after a workout", "stress" to "after a stressful day", "travel" to "after travel",
    "breathe" to "after breathing", "phone_in_bed" to "after using your phone in bed",
)

/** Short name (pills, tables) and sentence name per insight metric. */
internal val METRIC = mapOf(
    "sleep_score" to ("Sleep score" to "sleep score"), "deep_min" to ("Deep" to "deep sleep"), "rem_min" to ("REM" to "REM sleep"),
    "hrv" to ("HRV" to "HRV"), "rhr" to ("Resting HR" to "resting heart rate"), "total_min" to ("Time asleep" to "time asleep"),
    "awake_min" to ("Awake" to "time awake"), "bed_start" to ("Bedtime" to "bedtime"), "strain" to ("Strain" to "strain index"),
    "day_hr" to ("Daytime HR" to "daytime heart rate"), "wind_down" to ("Settling" to "settling time"),
    "felt" to ("Felt" to "how recovered you felt"), "stress_load" to ("Stress" to "stress load"), "steps" to ("Steps" to "steps"),
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
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text("What affects you", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard {
            kinds.forEachIndexed { i, (kind, k) ->
                if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                val ok = k["status"].str() == "ok"
                var open by remember { mutableStateOf(false) }
                val nWith = k["n_with"].num()?.roundToInt() ?: 0
                val name = if (kind == "sick") "Feeling unwell" else KIND_LABEL[kind] ?: kind
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
                        Text(if (open) "Less" else "More", style = AppType.footnote, color = c.tint)
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
                Hairline(Modifier.padding(start = AppSpace.l))
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptic.tick(); onTest() }.padding(AppSpace.l)) {
                    Text("Test it yourself", style = AppType.body, color = c.tint)
                    Text("Try one habit for 14 days and see what changes.", style = AppType.footnote, color = c.secondaryLabel)
                }
            }
        }
    }
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

/** Mark the moment: one thing the user named (`/marks.things[]`). "Mark now" stores a mark, a long press picks an earlier time today, Undo takes it back for 10 s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkCard(t: JsonObject, scope: CoroutineScope, reload: suspend () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val slot = t["slot"].num()?.roundToInt() ?: return
    var just by remember { mutableStateOf<Long?>(null) } // epoch ms of the mark Undo still takes back
    // Marked or undone on this card: shown at once, also while the server (or its cached answer) does not know yet.
    var added by remember { mutableStateOf(emptyList<Long>()) }
    var gone by remember { mutableStateOf(emptyList<Long>()) }
    var pick by remember { mutableStateOf(false) }
    fun put(ts: Long, cancel: Boolean) {
        if (cancel) { gone = gone + ts; just = null } else { added = added + ts; just = ts }
        scope.launch { mark(ctx, "mark_$slot", ts, cancel); reload() }
    }
    LaunchedEffect(just) { if (just != null) { delay(10_000); just = null } }
    val times = ((t["marks"] as? JsonArray)?.mapNotNull { it.num()?.toLong()?.times(1000) }.orEmpty() + added).distinct().filter { it !in gone }
    InsetCard {
        Column(Modifier.fillMaxWidth().padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
            // The chip (and Undo) drop under the name when the two do not fit on one line.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text(t["name"].str().orEmpty(), style = AppType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically).padding(end = AppSpace.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuggestChip(just?.let { "Marked ${clock(it / 1000.0)}" } ?: "Mark now", just != null, "Tap to mark now, hold for an earlier time", { haptic.tick(); pick = true }) {
                        haptic.tick(); put(System.currentTimeMillis() / 1000 * 1000, false)
                    }
                    just?.let { ts ->
                        Text(
                            "Undo", style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = c.tint,
                            modifier = Modifier.padding(start = AppSpace.xs).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { haptic.tick(); put(ts, true) }.padding(horizontal = AppSpace.m, vertical = 9.dp),
                        )
                    }
                }
            }
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
    if (pick) TimePickDialog(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), { pick = false }) { hhmm ->
        pick = false
        var ts = LocalDate.now().atTime(LocalTime.parse(hhmm)).atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
        if (ts > System.currentTimeMillis()) return@TimePickDialog Toast.makeText(ctx, "Pick a time earlier today.", Toast.LENGTH_SHORT).show()
        while (ts in gone) ts += 1000 // an undone second stays undone (the larger value wins): the new mark moves one on
        put(ts, false)
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

// Experiment kinds (backend insights.experiment) as the "do it" day's instruction; a "skip it" day reads "No …".
private val TEST_ON = mapOf(
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
