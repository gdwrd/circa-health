package com.nsheremet.circa.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nsheremet.circa.Prefs.reportName
import com.nsheremet.circa.Prefs.reportWriter
import com.nsheremet.circa.Prefs.sleepNeedHours
import com.nsheremet.circa.reportHash
import com.nsheremet.circa.writeReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// AI6: the Reports page. Each `/report` card shows its written note (reports.json, when its hash still matches) or its fallback.

/** [written] is the model's note, null when the card shows its [fallback]; [pending]: reports.json has no current entry (not written yet). */
internal data class ReportCard(val kind: String, val verdict: String?, val advice: List<String>, val fallback: String, val written: String?, val pending: Boolean) {
    val note get() = written ?: fallback
}

private val pageOrder = listOf("today", "night", "yesterday", "tonight", "week", "next_week", "body")

/** Writer off or no model: every card shows its fallback (PLAN12 "Empty and off states"). */
internal fun reportsOff(ctx: Context) = !ctx.reportWriter || !reportModel(ctx).exists()

/** `/report` cards in page order (`body` second when it says `watch`) with their notes from `filesDir/reports.json`. Blocking I/O. */
internal fun reportCards(ctx: Context, reply: JsonElement?): List<ReportCard> {
    val o = reply as? JsonObject ?: return emptyList()
    val date = (o["date"] as? JsonPrimitive)?.content ?: return emptyList()
    val saved = if (reportsOff(ctx)) null else runCatching { Json.parseToJsonElement(File(ctx.filesDir, "reports.json").readText()) as JsonObject }.getOrNull()
    val name = ctx.reportName
    return (o["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { c ->
        fun strs(k: String) = (c[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val kind = (c["kind"] as? JsonPrimitive)?.content.orEmpty()
        val verdict = (c["verdict"] as? JsonPrimitive)?.contentOrNull
        val fallback = (c["fallback"] as? JsonPrimitive)?.content.orEmpty()
        val e = saved?.get("$date/$kind") as? JsonObject
        val fresh = (e?.get("hash") as? JsonPrimitive)?.content == reportHash(name, verdict, strs("facts"), strs("advice"))
        ReportCard(kind, verdict, strs("advice"), fallback, (e?.get("text") as? JsonPrimitive)?.content?.takeIf { fresh && it != fallback }, !fresh)
    }.sortedBy { if (it.kind == "body" && it.verdict == "watch") 0.5 else pageOrder.indexOf(it.kind).let { i -> if (i < 0) 99.0 else i.toDouble() } }
}

/** Today's cards, or null when the backend gave nothing (not even a cached reply). */
internal suspend fun loadReport(ctx: Context): Pair<JsonElement, List<ReportCard>>? =
    Api.report(ctx, LocalDate.now())?.let { r -> r to withContext(Dispatchers.IO) { reportCards(ctx, r) } }

/** App provides both so the Today hero can grow into the page's first card. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.hero(): Modifier {
    val s = LocalShared.current ?: return this
    val a = LocalNavScope.current ?: return this
    return with(s) { this@hero.sharedBounds(rememberSharedContentState("report-hero"), a, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds) }
}

private fun title(kind: String): Pair<String, ImageVector> = when (kind) {
    "today" -> "Today" to Icons.Rounded.WbSunny
    "night" -> "Last night" to Icons.Rounded.Bedtime
    "yesterday" -> "Yesterday" to Icons.Rounded.DirectionsWalk
    "tonight" -> "Tonight" to Icons.Rounded.NightsStay
    "week" -> "This week" to Icons.Rounded.DateRange
    "next_week" -> "Next week" to Icons.Rounded.Flag
    else -> "Body signals" to Icons.Rounded.MonitorHeart
}

/** PLAN12 Cards table: the hero by its verdict, the others by kind. */
@Composable
internal fun accent(card: ReportCard): Color = AppTheme.colors.run {
    when (card.kind) {
        "today" -> when (card.verdict) { "push" -> activity; "easy" -> breathe; "rest" -> sleep; else -> readiness }
        "night" -> sleep
        "yesterday" -> activity
        "tonight" -> sleepLight
        "week" -> tint
        "next_week" -> breathe
        else -> heart
    }
}

@Composable
private fun wash(verdict: String?): Color = AppTheme.colors.run {
    when (verdict) { "push" -> pushWash; "easy" -> easyWash; "rest" -> restWash; else -> steadyWash }
}

/** Loading bars in place of a note: [lines] rounded bars one note line apart, the last shorter, a highlight sweeping across. */
@Composable
private fun NoteSkeleton(lines: Int) {
    val base = AppTheme.colors.emptySegment
    val shine = base.copy(alpha = base.alpha * 0.35f)
    val reduce = LocalReduceMotion.current
    val sweep by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    val lineH = with(LocalDensity.current) { AppType.note.lineHeight.toDp() }
    Canvas(Modifier.fillMaxWidth().height(lineH * lines)) {
        val bar = 11.dp.toPx()
        val x = (if (reduce) -1f else sweep) * size.width
        val brush = Brush.linearGradient(listOf(base, shine, base), Offset(x - size.width * 0.3f, 0f), Offset(x + size.width * 0.3f, 0f))
        repeat(lines) { i ->
            val w = size.width * if (i == lines - 1) 0.6f else 1f
            drawRoundRect(brush, Offset(0f, i * lineH.toPx() + (lineH.toPx() - bar) / 2), Size(w, bar), CornerRadius(bar / 2))
        }
    }
}

/**
 * One card. [onOpen] set = the compact form on Today: verdict word and the note's first two sentences, a chevron, no advice.
 * [writing]: a write is running, so a [ReportCard.pending] card shows loading bars until its note lands.
 */
@Composable
internal fun ReportCardView(card: ReportCard, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, writing: Boolean = false) {
    val skeleton = writing && card.pending && onOpen == null
    val c = AppTheme.colors
    val a = accent(card)
    val haptic = LocalHapticFeedback.current
    val hero = card.kind == "today"
    InsetCard(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (hero) wash(card.verdict).let { w -> Modifier.background(Brush.verticalGradient(listOf(w, w.copy(alpha = 0f)))) } else Modifier)
                .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { haptic.tick(); onOpen() } else Modifier)
                .padding(AppSpace.l),
        ) {
            val (name, icon) = title(card.kind)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(a.copy(alpha = 0.16f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = a, modifier = Modifier.size(16.dp))
                }
                Text(name, style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), color = a, modifier = Modifier.padding(start = AppSpace.s).weight(1f))
                if (onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open report", tint = c.secondaryLabel)
            }
            if (hero) Text(card.verdict.orEmpty().replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = AppType.largeTitle, color = a, modifier = Modifier.padding(top = AppSpace.m))
            // While writing: loading bars (null) first, the written note cross-fades in.
            Crossfade(
                if (skeleton) null else if (onOpen != null) card.note.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ") else card.note,
                // Capped measure: the full-width hero on an unfolded screen would run ~100 characters a line.
                Modifier.padding(top = if (hero) AppSpace.xs else AppSpace.m).widthIn(max = 640.dp).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                spring(stiffness = Spring.StiffnessMediumLow), label = "note",
            ) { if (it == null) NoteSkeleton(if (hero) 5 else 4) else Text(it, style = AppType.note) }
            // The fallback already ends with the advice lines: show a row only for a line it does not contain.
            val advice = card.advice.filter { card.written != null || skeleton || !card.fallback.contains(it.trim().removeSuffix("."), ignoreCase = true) }
            if (onOpen == null && advice.isNotEmpty()) {
                Hairline(Modifier.padding(top = AppSpace.l, bottom = AppSpace.m))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                    advice.forEach { line ->
                        Row {
                            Icon(Icons.Rounded.CheckCircle, null, tint = a, modifier = Modifier.size(20.dp))
                            Text(line, style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = AppSpace.s))
                        }
                    }
                }
            }
            // Fixed text from the app, never the model's (PLAN12: Body signals keeps today's rules).
            if (onOpen == null && card.kind == "body" && card.verdict == "watch") Text(
                "See a doctor if you feel unwell.", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.m),
            )
            if (onOpen == null) {
                val ctx = LocalContext.current
                var graph by remember { mutableStateOf<(@Composable (Color) -> Unit)?>(null) }
                LaunchedEffect(card.kind, card.verdict) { graph = runCatching { graphFor(ctx, card) }.getOrNull() }
                graph?.let { g ->
                    Hairline(Modifier.padding(top = AppSpace.l, bottom = AppSpace.s))
                    g(a)
                }
            }
        }
    }
}

private val GRAPH = 120.dp

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

/** Per-day [k] (divided by [div]) from a `/week` `days` or `/trends` `points` array. */
private fun daily(a: JsonElement?, k: String, div: Double = 1.0) = (a as? JsonArray).orEmpty().mapNotNull { it.obj() }
    .mapNotNull { o -> (o["date"] as? JsonPrimitive)?.content?.let { LocalDate.parse(it) to o[k].num()?.div(div) } }

private fun enough(p: List<Pair<LocalDate, Double?>>) = p.count { it.second != null } >= 2

/** The 7 `/sleep` replies the Sleep tab loads: today's reply and (night, session) for the week. */
private suspend fun nights(ctx: Context): Pair<JsonObject?, List<Pair<LocalDate, JsonObject?>>> = coroutineScope {
    val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    val all = days.map { d -> async { Api.sleep(ctx, d.toString()).obj() } }.awaitAll()
    all.last() to days.zip(all.map { it?.get("session").obj() })
}

/** PLAN12 Cards table: the card's graph from the endpoint its own screen uses, drawn in the accent it is given; null when there is too little to draw. */
private suspend fun graphFor(ctx: Context, card: ReportCard): (@Composable (Color) -> Unit)? {
    val today = LocalDate.now()
    suspend fun weekDays() = Api.week(ctx).obj()?.get("days")
    return when {
        card.kind == "today" -> daily(weekDays(), "readiness").takeIf(::enough)?.let { p -> { a -> MiniChart(null, p, a, false, GRAPH) } }
        card.kind == "night" -> {
            val d = Api.sleep(ctx, today.toString()).obj()
            val s = d?.get("session").obj() ?: return null
            val start = s["start"].num() ?: return null
            val end = s["end"].num() ?: return null
            val segs = segments(d?.get("stages") as? JsonArray, end).ifEmpty { return null }
            return { _ -> Hypnogram(segs, start, end, emptyList(), null, lane = 19.dp) }
        }
        card.kind == "yesterday" -> {
            val day = today.minusDays(1)
            val midnight = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            // As the Stress tab: 30-min slot since midnight and the stress value.
            val st = (Api.stress(ctx, day).obj()?.get("points") as? JsonArray).orEmpty().mapNotNull { o ->
                o.obj()?.let { p -> p["t"].num()?.let { t -> p["v"].num()?.let { ((t - midnight) / 1800).roundToInt() to it } } }
            }
            if (st.size >= 2) return { a -> ThroughTheDay(st, emptyList(), a, GRAPH, framed = false) }
            val rows = (Api.activity(ctx, day).obj()?.get("hours") as? JsonArray).orEmpty().mapNotNull { it.obj() }
                .mapNotNull { o -> o["h"].num()?.let { Hour(it.toInt(), o["steps"].num() ?: 0.0, (o["worn"] as? JsonPrimitive)?.content == "true") } }
            if (rows.sumOf { it.steps } <= 0) return null
            return { _ -> HourlySteps(rows, GRAPH) }
        }
        card.kind == "tonight" -> {
            val (d, week) = nights(ctx)
            val b = d?.get("bedtime").obj() ?: return null
            if ((b["status"] as? JsonPrimitive)?.content != "ok" || b["start_min"].num() == null || b["end_min"].num() == null) return null
            return { a -> Bedtime(b, week, d?.get("plan").obj(), a, framed = false) }
        }
        card.kind == "week" -> {
            val days = weekDays()
            val charts = listOf(
                Triple("Sleep · hours", daily(days, "sleep_min", 60.0), " h"),
                Triple("Steps · thousands", daily(days, "steps", 1000.0), "k"),
            ).filter { enough(it.second) }.ifEmpty { return null }
            return { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.m)) {
                    charts.forEach { (label, p, unit) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = AppType.footnote, color = AppTheme.colors.secondaryLabel)
                            MiniChart(null, p, a, true, GRAPH, unit)
                        }
                    }
                }
            }
        }
        card.kind == "next_week" && card.verdict == "regular" -> {
            val (d, week) = nights(ctx)
            if (week.none { it.second != null }) return null
            return { a -> Regularity(d?.get("contributors").obj()?.get("regularity").num(), week, d?.get("sleep_extra").obj()?.get("regularity").obj(), a, framed = false) }
        }
        card.kind == "next_week" && card.verdict == "move" ->
            daily(Api.trends(ctx, "cardio_load", 7).obj()?.get("points"), "value").takeIf(::enough)?.let { p -> { a -> MiniChart(null, p, a, true, GRAPH, "") } }
        card.kind == "next_week" -> {
            val need = ctx.sleepNeedHours.toDouble()
            daily(weekDays(), "sleep_min", 60.0).takeIf(::enough)?.let { p -> { a -> MiniChart(null, p, a, true, GRAPH, goal = need) } }
        }
        card.kind == "body" ->
            daily(Api.trends(ctx, "strain", 14).obj()?.get("points"), "value").takeIf(::enough)?.let { p -> { a -> MiniChart(null, p, a, false, GRAPH) } }
        else -> null
    }
}

@Composable
fun ReportScreen(backend: Boolean?, onBack: () -> Unit) {
    val c = AppTheme.colors
    val ctx = LocalContext.current
    val back: @Composable () -> Unit = {
        Text("‹ Today", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs))
    }
    if (backend != true) return LargeTitleScaffold("Reports", back = back) {
        if (backend == false) item { InsetCard { EmptyState(Icons.Rounded.CloudOff, "Reports need the backend", "Set the server URL and token in Settings.") } }
    }
    var cards by remember { mutableStateOf<List<ReportCard>?>(null) }
    var report by remember { mutableStateOf<JsonElement?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var off by remember { mutableStateOf(true) } // writer off or no model: every card shows its fallback
    var writing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Writes the notes, re-reading reports.json as they land; a model that fails to load ends it like a finished write.
    suspend fun write(r: JsonElement, force: Boolean) {
        writing = true
        try {
            coroutineScope {
                val job = launch(Dispatchers.Default) { runCatching { writeReports(ctx, force) } }
                while (job.isActive) {
                    delay(1500)
                    cards = withContext(Dispatchers.IO) { reportCards(ctx, r) }
                }
            }
            cards = withContext(Dispatchers.IO) { reportCards(ctx, r) }
        } finally { writing = false }
    }
    LaunchedEffect(Unit) {
        val r = loadReport(ctx)
        off = withContext(Dispatchers.IO) { reportsOff(ctx) }
        report = r?.first
        cards = r?.second
        loaded = true
        // The worker may have been killed before it wrote: write the missing notes now.
        if (r != null && !off && r.second.any { it.pending }) write(r.first, false)
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val haptic = LocalHapticFeedback.current
    val regenerate: @Composable () -> Unit = {
        val color = if (writing) c.secondaryLabel else c.tint
        Row(
            Modifier.heightIn(min = 44.dp).clip(AppShape.small).clickable(enabled = !writing, role = Role.Button) {
                haptic.tick()
                report?.let { r ->
                    cards = cards?.map { it.copy(written = null, pending = true) } // bars at once, before the writer drops the file's entries
                    scope.launch { write(r, true) }
                }
            }.padding(horizontal = AppSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val turn = if (writing) rememberInfiniteTransition(label = "spin")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "turn").value else 0f
            Icon(Icons.Rounded.Refresh, null, tint = color, modifier = Modifier.size(20.dp).rotate(turn))
            Text(if (writing) "Writing…" else "Regenerate", style = AppType.body, color = color, modifier = Modifier.padding(start = AppSpace.xs))
        }
    }
    LargeTitleScaffold("Reports", back = back, action = regenerate.takeIf { !off && !cards.isNullOrEmpty() }) {
        item {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), style = AppType.subhead, color = c.secondaryLabel,
                modifier = Modifier.padding(start = AppSpace.xs),
            )
        }
        val list = cards
        if (list.isNullOrEmpty()) {
            if (loaded) item { InsetCard { EmptyState(Icons.Rounded.AutoAwesome, "No report yet", if (list == null) "It appears once the server can be reached." else "It fills in after the next sync.") } }
            return@LargeTitleScaffold
        }
        item(key = list.first().kind) { ReportCardView(list.first(), Modifier.hero(), writing = writing) }
        val rest = list.drop(1)
        // 600 dp and wider: the small cards flow in two columns under the full-width hero.
        if (wide) item {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.l)) {
                listOf(0, 1).forEach { col ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpace.l)) {
                        rest.filterIndexed { i, _ -> i % 2 == col }.forEach { ReportCardView(it, writing = writing) }
                    }
                }
            }
        } else rest.forEach { card -> item(key = card.kind) { ReportCardView(card, writing = writing) } }
        if (off) item {
            val open = LocalOpenSettings.current
            InsetCard {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = open != null) { open?.invoke() }.padding(AppSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.tint, modifier = Modifier.size(20.dp))
                    Text("Turn on written reports", style = AppType.body, color = c.tint, modifier = Modifier.padding(start = AppSpace.m).weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel)
                }
            }
        }
        if (list.any { it.written != null }) item {
            Text(
                "Written on this phone by Gemma. Nothing left the device.", style = AppType.footnote, color = c.secondaryLabel,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpace.xl),
            )
        }
    }
}
