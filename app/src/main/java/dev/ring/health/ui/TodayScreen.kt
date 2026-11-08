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
                            (d[key].obj()?.get("contributors").obj() ?: JsonObject(emptyMap())).forEach { (name, v) ->
                                Hairline(Modifier.padding(start = AppSpace.l))
                                Contributor(NAMES[name] ?: name, v.num(), meta.second)
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
private fun Contributor(name: String, value: Double?, color: Color) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.l, vertical = AppSpace.m)) {
        Row {
            Text(name, style = AppType.body, modifier = Modifier.weight(1f))
            Text(value?.roundToInt()?.toString() ?: "—", style = AppType.body, color = if (value == null) c.secondaryLabel else c.label)
        }
        val bar = RoundedCornerShape(2.dp)
        Box(Modifier.padding(top = AppSpace.s).fillMaxWidth().height(4.dp).clip(bar).background(color.copy(alpha = 0.2f))) {
            if (value != null) Box(Modifier.fillMaxWidth((value / 100).coerceIn(0.0, 1.0).toFloat()).height(4.dp).clip(bar).background(color))
        }
    }
}
