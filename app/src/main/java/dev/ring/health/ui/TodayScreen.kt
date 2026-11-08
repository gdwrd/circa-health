package dev.ring.health.ui

import androidx.compose.animation.AnimatedVisibility
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
import java.util.Locale

private val NAMES = mapOf(
    "total" to "Total sleep", "efficiency" to "Efficiency", "restfulness" to "Restfulness", "rem" to "REM sleep",
    "deep" to "Deep sleep", "timing" to "Timing", "resting_hr" to "Resting heart rate", "hrv_balance" to "HRV balance",
    "sleep" to "Sleep", "sleep_balance" to "Sleep balance", "previous_day_activity" to "Previous day activity",
    "activity_balance" to "Activity balance", "recovery_index" to "Recovery index", "steps" to "Steps",
    "inactive_hours" to "Inactive hours", "calories" to "Calories", "goal_days" to "Goal days",
)

// Plain-language explanations shown when a score or contributor is tapped. Keep in sync with backend/app/scoring.py.
private val SCORE_INFO = mapOf(
    "sleep" to "How well you slept last night, from 0 to 100. It is the average of the contributors below: " +
        "time asleep against your sleep need, how much of the night you were asleep, minutes awake, REM and deep " +
        "sleep, and whether the middle of your sleep fell between midnight and 3 AM.",
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
    var sheet by remember { mutableStateOf(false) }
    var sheetDay by remember { mutableStateOf<Int?>(null) } // 0 Yesterday, 1 Today; null = by time of day
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
        Api.settings(ctx)?.get("medication_time").str()?.let { medTime = it }
    }

    val c = AppTheme.colors
    val rings = listOf("sleep" to ("Sleep" to c.sleep), "readiness" to ("Readiness" to c.readiness), "activity" to ("Activity" to c.activity))
    LargeTitleScaffold("Today") {
        val d = data ?: return@LargeTitleScaffold
        if (d["status"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "learning") item {
            InsetCard {
                Text(
                    "Learning your baseline · ${(d["baseline_days"] as? JsonPrimitive)?.contentOrNull ?: "?"} days",
                    style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l),
                )
            }
        }
        item {
            InsetCard {
                Row(Modifier.padding(horizontal = AppSpace.m, vertical = AppSpace.l)) {
                    rings.forEach { (key, meta) ->
                        val score = d[key].obj()?.get("score").num()
                        Column(
                            Modifier.weight(1f).clip(AppShape.small).clickable { open = if (open == key) null else key }
                                .padding(AppSpace.s),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ScoreRing(
                                (score ?: 0.0).toFloat() / 100f, meta.second,
                                Modifier.fillMaxWidth().aspectRatio(1f), stroke = 10.dp,
                            ) {
                                Text(score?.roundToInt()?.toString() ?: "—", style = AppType.title)
                            }
                            Text(
                                meta.first, style = AppType.footnote, modifier = Modifier.padding(top = AppSpace.s),
                                color = if (open == key) c.label else c.secondaryLabel,
                                fontWeight = if (open == key) FontWeight.SemiBold else null,
                            )
                        }
                    }
                }
                rings.forEach { (key, meta) ->
                    AnimatedVisibility(open == key) {
                        Column {
                            Hairline(Modifier.padding(start = AppSpace.l))
                            Column(Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
                                Text("What is ${meta.first}?", style = AppType.headline)
                                Text(SCORE_INFO[key].orEmpty(), style = AppType.subhead, modifier = Modifier.padding(top = AppSpace.xs))
                                Text(SCORE_SCALE, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s))
                            }
                            (d[key].obj()?.get("contributors").obj() ?: JsonObject(emptyMap())).forEach { (name, v) ->
                                Hairline(Modifier.padding(start = AppSpace.l))
                                Contributor(NAMES[name] ?: name, v.num(), meta.second, CONTRIBUTOR_INFO[name])
                            }
                        }
                    }
                }
            }
        }
        item {
            val v = d["vitals"].obj()
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                Text("Vitals", style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
                InsetCard {
                    listOf("Heart rate" to ("hr" to "bpm"), "HRV" to ("hrv" to "ms"), "SpO₂" to ("spo2" to "%"), "Ring battery" to ("battery" to "%"))
                        .forEachIndexed { i, (label, m) ->
                            if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                            val x = v?.get(m.first).num()
                            Row(Modifier.fillMaxWidth().padding(AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = AppType.body, modifier = Modifier.weight(1f))
                                Text(x?.roundToInt()?.toString() ?: "—", style = AppType.body, fontWeight = FontWeight.SemiBold)
                                if (x != null) Text(" ${m.second}", style = AppType.body, color = c.secondaryLabel)
                            }
                        }
                }
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
    }
    if (sheet) CheckInSheet(journal, medTime, scope, sheetDay, ::loadJournal) { sheet = false; sheetDay = null }
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
