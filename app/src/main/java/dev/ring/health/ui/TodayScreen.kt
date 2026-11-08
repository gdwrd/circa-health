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
fun TodayScreen(backend: Boolean?) {
    if (backend != true) return PlaceholderTab("Today", backend)
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    // The tab leaves composition when deselected, so this also reloads on reselect.
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var sheet by remember { mutableStateOf(false) }
    suspend fun loadTags() {
        tags = (Api.tags(ctx, 1).obj()?.get("tags") as? JsonArray)?.mapNotNull { (it.obj()?.get("tag") as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }
    LaunchedEffect(Unit) { data = Api.today(ctx).obj(); loadTags() }

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
            InsetCard {
                if (tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(start = AppSpace.l, top = AppSpace.l, end = AppSpace.l),
                        horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s),
                    ) { tags.forEach { TagChip(it, false) {} } }
                    Hairline(Modifier.padding(start = AppSpace.l, top = AppSpace.m))
                }
                Text(
                    "+ Add tag", style = AppType.body, color = c.tint,
                    modifier = Modifier.fillMaxWidth().clickable { sheet = true }.padding(AppSpace.l),
                )
            }
        }
    }
    if (sheet) AddTagSheet(
        onDismiss = { sheet = false },
        onAdd = { tag, note -> if (Api.addTag(ctx, tag, note)) { loadTags(); sheet = false } },
    )
}

private val QUICK = listOf("Alcohol", "Caffeine", "Late meal", "Workout", "Sick", "Travel", "Stress")

@Composable
private fun TagChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Text(
        text, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), color = if (selected) Color.White else c.label,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.tint else c.secondaryLabel.copy(alpha = 0.16f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddTagSheet(onDismiss: () -> Unit, onAdd: suspend (String, String?) -> Unit) {
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.padding(horizontal = AppSpace.l).padding(bottom = AppSpace.xl), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
            Text("Add tag", style = AppType.title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpace.s), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                QUICK.forEach { q -> TagChip(q, text == q) { text = q } }
            }
            Box(Modifier.fillMaxWidth().clip(AppShape.small).background(c.card).padding(AppSpace.l)) {
                if (text.isEmpty()) Text("Or type your own", style = AppType.body, color = c.secondaryLabel)
                BasicTextField(
                    text, { text = it.take(40) }, singleLine = true, textStyle = AppType.body.copy(color = c.label),
                    cursorBrush = SolidColor(c.tint), modifier = Modifier.fillMaxWidth(),
                )
            }
            val ok = text.isNotBlank() && !busy
            Text(
                "Add", style = AppType.headline, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(AppShape.small).background(if (ok) c.tint else c.tint.copy(alpha = 0.4f))
                    .clickable(enabled = ok) { busy = true; scope.launch { onAdd(text.trim(), null); busy = false } }
                    .padding(vertical = AppSpace.m),
            )
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
