package com.nsheremet.circa.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nsheremet.circa.pushSample
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The PHQ-9 as published (phqscreeners.com, PHQ-9_English.pdf); do not reword.
private const val STEM = "Over the last 2 weeks, how often have you been bothered by any of the following problems?"
private val ITEMS = listOf(
    "Little interest or pleasure in doing things",
    "Feeling down, depressed, or hopeless",
    "Trouble falling or staying asleep, or sleeping too much",
    "Feeling tired or having little energy",
    "Poor appetite or overeating",
    "Feeling bad about yourself — or that you are a failure or have let yourself or your family down",
    "Trouble concentrating on things, such as reading the newspaper or watching television",
    "Moving or speaking so slowly that other people could have noticed? Or the opposite — being so fidgety or restless that you have been moving around a lot more than usual",
    "Thoughts that you would be better off dead or of hurting yourself in some way",
)
private val ANSWERS = listOf("Not at all", "Several days", "More than half the days", "Nearly every day")
private const val CREDIT = "Developed by Drs. Robert L. Spitzer, Janet B.W. Williams, Kurt Kroenke and colleagues"
private const val SAFETY = "You marked thoughts of being better off dead or of hurting yourself. Please talk to your doctor or someone you trust " +
    "today. If you might act on them, call your local emergency number now."

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

internal fun phqBand(total: Int) = when {
    total <= 4 -> "Minimal"
    total <= 9 -> "Mild"
    total <= 14 -> "Moderate"
    total <= 19 -> "Moderately severe"
    else -> "Severe"
}

/**
 * The PHQ-9, one question a page. A tap answers and moves on; Back goes to the previous question with its answer kept.
 * Nothing is stored until the ninth answer: then `phq9_total` and `phq9_item9` at that second. Leaving earlier stores nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhqSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = (ctx as ComponentActivity).lifecycleScope // outlives the sheet, so a closed sheet still finishes its push
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val answers = remember { IntArray(9) { -1 } }
    var page by remember { mutableIntStateOf(0) } // 0..8 questions, 9 the result
    // The last total before this one (ts s, total), read when the sheet opens so the new one is never "last time".
    var last by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(Unit) {
        last = (Api.mind(ctx, 365)?.get("phq") as? JsonArray)?.mapNotNull { e ->
            e.obj()?.let { o -> o["ts"].num()?.toLong()?.let { it to (o["total"].num()?.toInt() ?: return@mapNotNull null) } }
        }?.maxByOrNull { it.first }
    }
    fun answer(v: Int) {
        haptic.tick()
        answers[page] = v
        if (page == 8) {
            val ts = System.currentTimeMillis()
            val total = answers.sum()
            scope.launch {
                pushSample(ctx, "phq9_total", total.toDouble(), ts)
                pushSample(ctx, "phq9_item9", answers[8].toDouble(), ts)
            }
        }
        page++
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        BackHandler { if (page in 1..8) page-- else onDismiss() }
        // 92 %: a fixed height (pages differ in length) that leaves the page behind in view, as an iOS sheet does.
        Column(Modifier.fillMaxHeight(0.92f).verticalScroll(rememberScrollState()).padding(horizontal = AppSpace.l).padding(bottom = AppSpace.xl)) {
            // Header: Cancel / Done, and a quiet "3 of 9" over a thin bar.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (page == 9) "Done" else "Cancel", style = AppType.body.copy(fontWeight = if (page == 9) FontWeight.SemiBold else FontWeight.Normal),
                    color = c.tint, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDismiss).padding(vertical = AppSpace.m),
                )
                Box(Modifier.weight(1f))
                Text(if (page < 9) "${page + 1} of 9" else "Two-minute check", style = AppType.subhead, color = c.secondaryLabel)
            }
            val progress by animateFloatAsState(
                (page + 1).coerceAtMost(9) / 9f,if (reduce) spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh) else spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
                label = "progress",
            )
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(c.emptySegment)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(50)).background(c.mind))
            }
            AnimatedContent(
                page, label = "phq",
                transitionSpec = {
                    val move = spring<androidx.compose.ui.unit.IntOffset>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
                    when {
                        reduce -> EnterTransition.None togetherWith ExitTransition.None
                        targetState > initialState ->(slideInHorizontally(move) { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(move) { -it / 3 } + fadeOut())
                        else -> (slideInHorizontally(move) { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(move) { it / 3 } + fadeOut())
                    }
                },
            ) { p ->
                if (p < 9) Column(Modifier.fillMaxWidth().padding(top = AppSpace.xl), verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                    Text(STEM, style = AppType.subhead, color = c.secondaryLabel)
                    Text(ITEMS[p], style = AppType.title, modifier = Modifier.padding(bottom = AppSpace.s))
                    InsetCard {
                        ANSWERS.forEachIndexed { v, a ->
                            if (v > 0) Hairline(Modifier.padding(start = AppSpace.l))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { answer(v) }.padding(horizontal = AppSpace.l, vertical = AppSpace.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(a, style = AppType.body, modifier = Modifier.weight(1f))
                                if (answers[p] == v) Icon(Icons.Rounded.Check, "Chosen", tint = c.mind, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(CREDIT, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, top = AppSpace.s))
                } else Result(answers.sum(), answers[8], last)
            }
        }
    }
}

@Composable
private fun Result(total: Int, item9: Int, last: Pair<Long, Int>?) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = AppSpace.xl), verticalArrangement = Arrangement.spacedBy(AppSpace.l)) {
        if (item9 > 0) InsetCard {
            Text(SAFETY, style = AppType.headline, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, top = AppSpace.l, bottom = AppSpace.m))
            Hairline(Modifier.padding(start = AppSpace.l))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://findahelpline.com")))
                }.padding(horizontal = AppSpace.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("findahelpline.com", style = AppType.body, color = c.tint, modifier = Modifier.weight(1f))
                Text("›", style = AppType.body, color = c.secondaryLabel)
            }
        }
        InsetCard {
            Column(Modifier.padding(AppSpace.l)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$total", style = AppType.display)
                    Text(" of 27", style = AppType.headline, color = c.secondaryLabel, modifier = Modifier.padding(bottom = AppSpace.s))
                }
                Text(phqBand(total), style = AppType.title, color = c.mind)
                last?.let { (ts, prev) ->
                    val on = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                    val d = total - prev
                    Text(
                        when { d > 0 -> "Up $d from $prev on $on"; d < 0 -> "Down ${-d} from $prev on $on"; else -> "The same as on $on" },
                        style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
                    )
                }
            }
        }
        Text(
            "The questionnaire's bands, not a diagnosis.", style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(horizontal = AppSpace.l),
        )
    }
}
