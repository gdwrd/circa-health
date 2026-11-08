package dev.ring.health.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
    if (backend != true) return PlaceholderTab("Stress", backend)
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) { data = Api.today(ctx).obj() }

    val c = AppTheme.colors
    LargeTitleScaffold("Stress") {
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
                        Text("No stress data yet today", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l))
                    } else {
                        Row(Modifier.fillMaxWidth().padding(AppSpace.l), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            zones.filter { it.second > 0 }.forEach { (_, min, col) ->
                                Box(Modifier.weight(min.toFloat()).height(16.dp).clip(CircleShape).background(col))
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
                    Column(Modifier.padding(AppSpace.l), verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        if (idx < 0) {
                            Text(
                                "Learning · ${(d["baseline_days"] as? JsonPrimitive)?.contentOrNull ?: "?"} days",
                                style = AppType.headline,
                            )
                            Text("Resilience appears once 5 of the last 14 days have data.", style = AppType.subhead, color = c.secondaryLabel)
                        } else {
                            Text(LEVELS[idx].first.replaceFirstChar { it.uppercase() }, style = AppType.largeTitle)
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                                repeat(5) { i ->
                                    Box(
                                        Modifier.weight(1f).height(6.dp).clip(CircleShape)
                                            .background(if (i <= idx) c.readiness else c.readiness.copy(alpha = 0.2f)),
                                    )
                                }
                            }
                            Text(LEVELS[idx].second, style = AppType.subhead, color = c.secondaryLabel)
                        }
                    }
                }
            }
        }
    }
}
