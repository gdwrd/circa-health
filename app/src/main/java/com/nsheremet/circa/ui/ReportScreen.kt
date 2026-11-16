package com.nsheremet.circa.ui

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.SmokingRooms
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelfImprovement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.work.WorkManager
import com.nsheremet.circa.SyncWorker
import com.nsheremet.circa.Prefs.reportWriter
import com.nsheremet.circa.writeReports
import com.nsheremet.circa.writeWeek
import com.nsheremet.circa.updateSince
import com.nsheremet.circa.updates
import com.nsheremet.circa.writeUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.Locale
import kotlin.math.roundToInt

// AI6: the Reports page. Each `/report` card shows its written note (reports.json, when its hash still matches) or its fallback.

/** [written] is the model's note, null when the card shows its [fallback]; [pending]: reports.json has no entry (not written yet); [date]: the report's day. */
internal data class ReportCard(val kind: String, val verdict: String?, val advice: List<String>, val fallback: String, val written: String?, val pending: Boolean, val date: LocalDate) {
    val note get() = written ?: fallback
}

private val pageOrder = listOf("today", "night", "yesterday", "tonight", "body")

/** Writer off or no model: every card shows its fallback (PLAN12 "Empty and off states"). */
internal fun reportsOff(ctx: Context) = !ctx.reportWriter || !reportModel(ctx).exists()

/** `/report` cards in page order (`body` second when it says `watch`) with their notes from `filesDir/reports.json`. Blocking I/O. */
internal fun reportCards(ctx: Context, reply: JsonElement?): List<ReportCard> {
    val o = reply as? JsonObject ?: return emptyList()
    val date = (o["date"] as? JsonPrimitive)?.content ?: return emptyList()
    return cardsOf(o, date, if (reportsOff(ctx)) null else savedReports(ctx))
        .sortedBy { if (it.kind == "body" && it.verdict == "watch") 0.5 else pageOrder.indexOf(it.kind).let { i -> if (i < 0) 99.0 else i.toDouble() } }
}

/** LW5: `/report/week` cards in the backend's order with their notes from `filesDir/weeks.json`; advice lines end with a full stop. Blocking I/O. */
internal fun weekCards(ctx: Context, reply: JsonObject?): List<ReportCard> {
    val start = (reply?.get("start") as? JsonPrimitive)?.content ?: return emptyList()
    return cardsOf(reply, start, if (reportsOff(ctx)) null else savedReports(ctx, "weeks.json"))
        .map { c -> c.copy(advice = c.advice.map { if (it.trimEnd().endsWith(".")) it else it.trimEnd() + "." }) }
}

/** "9 to 15 Nov"; across months "30 Nov to 6 Dec". */
private fun weekRange(reply: JsonObject?): String {
    val s = (reply?.get("start") as? JsonPrimitive)?.content?.let(LocalDate::parse) ?: return ""
    val e = (reply["end"] as? JsonPrimitive)?.content?.let(LocalDate::parse) ?: s.plusDays(6)
    val m = java.time.format.DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    return "${s.dayOfMonth}${if (s.month != e.month) " " + s.format(m) else ""} to ${e.dayOfMonth} ${e.format(m)}"
}

/** The reply's cards with their stored notes, `saved` keyed `"<day>/<kind>"`. */
private fun cardsOf(o: JsonObject, day: String, saved: JsonObject?): List<ReportCard> =
    (o["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { c ->
        val kind = (c["kind"] as? JsonPrimitive)?.content.orEmpty()
        val fallback = (c["fallback"] as? JsonPrimitive)?.content.orEmpty()
        // DT1: a stored note stays whatever the card's hash is now (written once a day).
        val e = saved?.get("$day/$kind") as? JsonObject
        ReportCard(kind, (c["verdict"] as? JsonPrimitive)?.contentOrNull, (c["advice"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            fallback, (e?.get("text") as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != fallback }, e == null || (e["text"] as? JsonPrimitive)?.content.isNullOrEmpty(), LocalDate.parse(day))
    }

/** [date]'s cards, or null when the backend gave nothing (not even a cached reply). */
internal suspend fun loadReport(ctx: Context, date: LocalDate = LocalDate.now()): Pair<JsonElement, List<ReportCard>>? =
    Api.report(ctx, date)?.let { r -> r to withContext(Dispatchers.IO) { reportCards(ctx, r) } }

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
    "week_overview" -> "Overview" to Icons.Rounded.DateRange
    "week_sleep" -> "Sleep" to Icons.Rounded.Bedtime
    "week_activity" -> "Activity" to Icons.Rounded.DirectionsWalk
    "week_body" -> "Body" to Icons.Rounded.MonitorHeart
    "week_mind" -> "Mind" to Icons.Rounded.SelfImprovement
    "week_effects" -> "What went with what" to Icons.Rounded.Flag
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
        "week_overview" -> tint
        "week_sleep" -> sleep
        "week_activity" -> activity
        "week_mind" -> breathe
        "week_effects" -> readiness
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
 * [rail] (DT4, the page's hero): the note becomes the timeline's morning node written at [morningAt]; [after] draws the rest of the rail.
 */
@Composable
internal fun ReportCardView(
    card: ReportCard, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, writing: Boolean = false,
    rail: Boolean = false, morningAt: Long? = null, after: (@Composable (Color) -> Unit)? = null,
) {
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
            val (title, icon) = title(card.kind)
            // DT4: an earlier day's first card is named by its weekday, not "Today".
            val name = if (hero && card.date != LocalDate.now()) card.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) else title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(a.copy(alpha = 0.16f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = a, modifier = Modifier.size(16.dp))
                }
                Text(name, style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), color = a, modifier = Modifier.padding(start = AppSpace.s).weight(1f))
                if (onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open report", tint = c.secondaryLabel)
            }
            if (hero) Text(card.verdict.orEmpty().replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = AppType.largeTitle, color = a, modifier = Modifier.padding(top = AppSpace.m))
            if (rail) {
                // DT4: the morning note is the rail's first node; [after] adds the day's events, updates and the pill.
                RailRow(a, first = true, last = after == null, node = 28.dp, nodeTop = 0.dp, dot = {
                    Box(Modifier.size(28.dp).background(a, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.WbSunny, null, tint = onTint(), modifier = Modifier.size(16.dp)) }
                }) {
                    Column {
                        NodeHeader(morningAt?.toDouble(), "Morning", a)
                        CardBody(card, a, onOpen, skeleton, hero)
                    }
                }
                after?.invoke(a)
            } else CardBody(card, a, onOpen, skeleton, hero)
        }
    }
}

@Composable
private fun CardBody(card: ReportCard, a: Color, onOpen: (() -> Unit)?, skeleton: Boolean, hero: Boolean) {
    val c = AppTheme.colors
    Column {
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
                AdviceRows(advice, a)
            }
            // Fixed text from the app, never the model's (PLAN12: Body signals keeps today's rules).
            if (onOpen == null && card.kind == "body" && card.verdict == "watch") Text(
                "See a doctor if you feel unwell.", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.m),
            )
            if (onOpen == null) {
                val ctx = LocalContext.current
                var graph by remember { mutableStateOf<(@Composable (Color) -> Unit)?>(null) }
                LaunchedEffect(card.kind, card.verdict, card.date) { graph = runCatching { graphFor(ctx, card) }.getOrNull() }
                graph?.let { g ->
                    Hairline(Modifier.padding(top = AppSpace.l, bottom = AppSpace.s))
                    g(a)
                }
            }
    }
}

@Composable
private fun AdviceRows(advice: List<String>, a: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppSpace.m)) {
        advice.forEach { line ->
            Row {
                Icon(Icons.Rounded.CheckCircle, null, tint = a, modifier = Modifier.size(20.dp))
                Text(line, style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = AppSpace.s))
            }
        }
    }
}

/** White on the accent in both themes, as the Today screen's filled chips. */
@Composable
private fun onTint() = AppTheme.colors.run { if (background.luminance() < 0.5f) label else card }

/**
 * One timeline entry: a 28 dp gutter holding [dot] ([node] tall, [nodeTop] down) on the rail, then [content].
 * Rows touch, so each draws its own rail segments above and below its node and the rail reads as one line.
 */
@Composable
private fun RailRow(a: Color, first: Boolean, last: Boolean, node: Dp, nodeTop: Dp, dot: @Composable () -> Unit, content: @Composable () -> Unit) {
    val gap = if (first) 0.dp else AppSpace.l
    val rail = a.copy(alpha = 0.3f)
    Row(
        Modifier.fillMaxWidth().drawBehind {
            val x = 14.dp.toPx()
            val top = (gap + nodeTop).toPx()
            val pad = 4.dp.toPx()
            if (!first) drawLine(rail, Offset(x, 0f), Offset(x, top - pad), 2.dp.toPx())
            if (!last) drawLine(rail, Offset(x, top + node.toPx() + pad), Offset(x, size.height), 2.dp.toPx()) // butt ends: segments meet without darker seams
        }.padding(top = gap),
    ) {
        Box(Modifier.width(28.dp).padding(top = nodeTop), contentAlignment = Alignment.TopCenter) { dot() }
        Box(Modifier.weight(1f).padding(start = AppSpace.m)) { content() }
    }
}

/** A note's first line, 28 dp tall to sit level with its node: the time in the time column, then what it is. */
@Composable
private fun NodeHeader(at: Double?, what: String, a: Color) {
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (at != null) TimeCol(at)
        Text(what, style = AppType.footnote.copy(fontWeight = FontWeight.SemiBold), color = a)
    }
}

@Composable
private fun TimeCol(at: Double) =
    Text(clock(at), style = AppType.footnote.copy(fontFeatureSettings = "tnum"), color = AppTheme.colors.secondaryLabel, modifier = Modifier.width(if (clock(0.0).length > 5) 68.dp else 48.dp)) // "12:55 PM" needs more than "12:55"

private fun JsonObject.t() = this["t"].num() ?: 0.0
private fun JsonObject.isEvening() = (this["evening"] as? JsonPrimitive)?.content == "true"

/** `/timeline` events in rows: an event within half an hour of a row's first event joins that row. */
internal fun halfHours(events: List<JsonObject>): List<List<JsonObject>> =
    events.sortedBy { it.t() }.fold(mutableListOf<MutableList<JsonObject>>()) { rows, e ->
        if (rows.isNotEmpty() && e.t() - rows.last().first().t() < 1800) rows.last() += e else rows += mutableListOf(e)
        rows
    }

/** The tinted chip the Today screen uses for that kind: a disc in its colour, intakes a quiet rounded square. */
@Composable
private fun EventChip(kind: String) {
    val c = AppTheme.colors
    val (icon, color) = when (kind) {
        "tired" -> Icons.Rounded.Bedtime to c.sleep
        "flat" -> Icons.Rounded.HorizontalRule to c.stageCore
        "wired" -> Icons.Rounded.Bolt to c.stageAwake
        "good" -> Icons.Rounded.WbSunny to c.readiness
        "wake" -> Icons.Rounded.WbTwilight to c.sleep
        "walk", "steps" -> Icons.Rounded.DirectionsWalk to c.activity
        "ride" -> Icons.Rounded.DirectionsBike to c.activity
        "workout" -> Icons.Rounded.FitnessCenter to c.activity
        "coffee" -> Icons.Rounded.Coffee to null
        "alcohol" -> Icons.Rounded.WineBar to null
        "meal" -> Icons.Rounded.Restaurant to null
        "dose" -> Icons.Rounded.Medication to null
        "snack" -> Icons.Rounded.Cookie to null
        "energy" -> Icons.Rounded.ElectricBolt to null
        "nicotine" -> Icons.Rounded.SmokingRooms to null
        "nap" -> Icons.Rounded.Hotel to null
        else -> Icons.Rounded.HorizontalRule to null
    }
    Box(
        Modifier.size(24.dp).background((color ?: c.secondaryLabel).copy(alpha = 0.14f), if (color != null) CircleShape else RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color ?: c.label, modifier = Modifier.size(14.dp)) }
}

/** One quiet row: time, the chips, the labels. */
@Composable
private fun EventRow(row: List<JsonObject>) {
    Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        TimeCol(row.first().t())
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) { row.forEach { EventChip((it["kind"] as? JsonPrimitive)?.content.orEmpty()) } }
        Text(
            row.joinToString(", ") { (it["label"] as? JsonPrimitive)?.content.orEmpty() },
            style = AppType.subhead, color = AppTheme.colors.secondaryLabel, modifier = Modifier.padding(start = AppSpace.s),
        )
    }
}

/** An update entry; no `text` = being written (loading bars), the note cross-fades in when it lands. */
@Composable
private fun UpdateEntry(u: JsonObject, a: Color, what: String) {
    Column {
        NodeHeader(u["at"].num() ?: (System.currentTimeMillis() / 1000.0), what, a)
        Crossfade(
            (u["text"] as? JsonPrimitive)?.content,
            Modifier.padding(top = AppSpace.xs).widthIn(max = 640.dp).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            spring(stiffness = Spring.StiffnessMediumLow), label = "update",
        ) { if (it == null) NoteSkeleton(3) else Text(it, style = AppType.note) }
        val advice = (u["advice"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (advice.isNotEmpty()) AdviceRows(advice, a, Modifier.padding(top = AppSpace.m))
    }
}

/** "Now": a dot in the accent with a soft ring breathing out of it; still under reduce-motion. */
@Composable
private fun NowDot(a: Color) {
    val reduce = LocalReduceMotion.current
    val p = if (reduce) 0f else rememberInfiniteTransition(label = "now")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse").value
    Canvas(Modifier.size(10.dp)) {
        if (!reduce) drawCircle(a.copy(alpha = 0.35f * (1 - p)), radius = size.minDimension / 2 * (1 + 1.4f * p))
        drawCircle(a)
    }
}

/** Ring sync first, as Settings' "Sync now" ([SyncWorker.runNow]), then wait for that run to end, at most 90 s. */
private suspend fun syncAndWait(ctx: Context) {
    val wm = WorkManager.getInstance(ctx)
    val flow = wm.getWorkInfosForUniqueWorkFlow("ring_sync")
    val before = flow.first().map { it.id }.toSet()
    SyncWorker.runNow(ctx)
    withTimeoutOrNull(90_000) {
        val mine = flow.first { l -> l.any { it.id !in before } }.filter { it.id !in before }
        // Two new runs: ours already ended and chained the next one (its REPLACE deletes ours, hence null).
        if (mine.size == 1) wm.getWorkInfoByIdFlow(mine[0].id).first { it == null || it.state.isFinished }
    }
}

private fun savedReports(ctx: Context, name: String = "reports.json"): JsonObject =
    runCatching { Json.parseToJsonElement(File(ctx.filesDir, name).readText()) as JsonObject }.getOrDefault(JsonObject(emptyMap()))

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
    val today = card.date
    // LW5: a week section's [ReportCard.date] is its week's start.
    if (card.kind == "week_sleep" || card.kind == "week_activity") {
        val days = (Api.reportWeek(ctx, today.toString())?.get("days") as? JsonArray).orEmpty().mapNotNull { it.obj() }
        if (days.size != 7) return null
        return { a -> WeekGraph(days, a, card.kind == "week_sleep") }
    }
    // /week, /trends and the bedtime plan have no date: an earlier day shows only its night and yesterday graphs.
    if (today != LocalDate.now() && card.kind != "night" && card.kind != "yesterday") return null
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
        card.kind == "body" ->
            daily(Api.trends(ctx, "strain", 14).obj()?.get("points"), "value").takeIf(::enough)?.let { p -> { a -> MiniChart(null, p, a, false, GRAPH) } }
        else -> null
    }
}

/**
 * LW5: seven bars Mon to Sun from a `/report/week` reply's `days`, the value over each, weekday letters under; a null day
 * is a dash, never a zero bar. [sleep]: hours asleep, a dashed line at the need and a lane of fell-asleep dots above
 * (`bed_min` = minutes after noon); else steps, a dot under each workout day.
 */
@Composable
private fun WeekGraph(days: List<JsonObject>, a: Color, sleep: Boolean) {
    val c = AppTheme.colors
    val tm = rememberTextMeasurer()
    val small = AppType.footnote.copy(color = c.secondaryLabel, fontFeatureSettings = "tnum")
    val lineH = with(LocalDensity.current) { small.lineHeight.toDp() }
    val v = days.map { if (sleep) it["sleep_min"].num()?.div(60) else it["steps"].num() }
    val need = if (sleep) days.firstNotNullOfOrNull { it["need_min"].num() }?.div(60) else null
    val beds = days.map { if (sleep) it["bed_min"].num() else null }
    val work = days.map { !sleep && (it["workout_min"].num() ?: 0.0) > 0 }
    val letters = days.map { (it["date"] as? JsonPrimitive)?.content?.let(LocalDate::parse)?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()).orEmpty() }
    val noon = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond().toDouble()
    val lo = beds.filterNotNull().minOrNull()
    val hi = beds.filterNotNull().maxOrNull()
    val lane = if (lo != null) 52.dp else 0.dp
    val gap = if (lo != null) AppSpace.m else 0.dp
    val marks = if (work.any { it }) 14.dp else AppSpace.xs
    Column {
        Text(if (sleep) "Hours asleep · dashed line: your need · dots: fell asleep" else "Steps · dot: workout day", style = AppType.footnote, color = c.secondaryLabel)
        Canvas(Modifier.fillMaxWidth().padding(top = AppSpace.s).height(lane + gap + lineH + GRAPH + marks + lineH)) {
            fun label(s: String) = tm.measure(s, small)
            val loL = lo?.let { label(clock(noon + it * 60)) }
            val hiL = hi?.takeIf { it != lo }?.let { label(clock(noon + it * 60)) }
            val needL = need?.let { label("%.1f h".format(it)) }
            val gutter = listOfNotNull(loL, hiL, needL).maxOfOrNull { it.size.width }?.let { it + AppSpace.s.toPx() } ?: 0f
            val slot = (size.width - gutter) / 7
            fun cx(i: Int) = gutter + slot * (i + 0.5f)
            // Fell-asleep lane: earlier on top, at least an hour tall so a steady week does not look jumpy.
            if (lo != null && hi != null) {
                val r = 4.dp.toPx()
                val span = maxOf(hi - lo, 60.0)
                val mid = (lo + hi) / 2
                val laneH = lane.toPx()
                loL?.let { drawText(it, topLeft = Offset(0f, 0f)) }
                hiL?.let { drawText(it, topLeft = Offset(0f, laneH - it.size.height)) }
                beds.forEachIndexed { i, b ->
                    if (b != null) drawCircle(c.sleepLight, r, Offset(cx(i), r + ((b - mid) / span + 0.5).toFloat() * (laneH - 2 * r)))
                }
            }
            val base = (lane + gap + lineH + GRAPH).toPx()
            val h = GRAPH.toPx()
            val top = maxOf(v.filterNotNull().maxOrNull() ?: 0.0, need ?: 0.0).takeIf { it > 0 } ?: 1.0
            val w = minOf(slot * 0.55f, 22.dp.toPx())
            // The need line goes under the bars and the value labels, so it never crosses a number.
            if (need != null && needL != null) {
                val y = base - (need / top).toFloat() * h
                drawLine(c.secondaryLabel, Offset(gutter, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                drawText(needL, topLeft = Offset(0f, y - needL.size.height / 2))
            }
            v.forEachIndexed { i, x ->
                if (x == null) {
                    val t = label("–")
                    drawText(t, topLeft = Offset(cx(i) - t.size.width / 2, base - t.size.height))
                    return@forEachIndexed
                }
                val bh = maxOf((x / top).toFloat() * h, 2.dp.toPx())
                drawRoundRect(a, Offset(cx(i) - w / 2, base - bh), Size(w, bh), CornerRadius(minOf(4.dp.toPx(), w / 2)))
                val t = label(if (sleep) "%.1f".format(x) else "${(x / 1000).roundToInt()}k")
                val tl = Offset(cx(i) - t.size.width / 2, base - bh - t.size.height)
                val pad = 4.dp.toPx() // keeps the dashes clear of the digits, or "6.9" reads "-6.9"
                drawRect(c.card, tl - Offset(pad, 0f), Size(t.size.width + 2 * pad, t.size.height.toFloat()))
                drawText(t, topLeft = tl)
                if (work[i]) drawCircle(a, 3.dp.toPx(), Offset(cx(i), base + 7.dp.toPx()))
            }
            letters.forEachIndexed { i, s ->
                val t = label(s)
                drawText(t, topLeft = Offset(cx(i) - t.size.width / 2, base + marks.toPx()))
            }
        }
    }
}

/** LW5: the Reports page's Last week card: the date range, the overview note, the focus line as its close. */
@Composable
private fun LastWeekCard(overview: ReportCard, range: String, writing: Boolean, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    InsetCard {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { haptic.tick(); onOpen() }.padding(AppSpace.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(c.tint.copy(alpha = 0.16f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DateRange, null, tint = c.tint, modifier = Modifier.size(16.dp))
                }
                Text("Last week", style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.tint, modifier = Modifier.padding(start = AppSpace.s).weight(1f))
                Text(range, style = AppType.footnote, color = c.secondaryLabel)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open last week", tint = c.secondaryLabel)
            }
            Crossfade(
                if (writing && overview.pending) null else overview.note,
                Modifier.padding(top = AppSpace.m).widthIn(max = 640.dp).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                spring(stiffness = Spring.StiffnessMediumLow), label = "week",
            ) { if (it == null) NoteSkeleton(4) else Text(it, style = AppType.note) }
            overview.advice.firstOrNull()?.let {
                Hairline(Modifier.padding(top = AppSpace.l, bottom = AppSpace.m))
                AdviceRows(listOf(it), c.tint)
            }
        }
    }
}

/** LW5: the week from [start] (a Monday): the overview, then one card per section with its note, advice and graph. */
@Composable
private fun WeekPage(start: String, writing: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c = AppTheme.colors
    val ctx = LocalContext.current
    var reply by remember { mutableStateOf<JsonObject?>(null) }
    var cards by remember { mutableStateOf<List<ReportCard>>(emptyList()) }
    LaunchedEffect(start, writing) {
        reply = Api.reportWeek(ctx, start)
        // While a write runs, re-read weeks.json as the notes land; the effect restarts once it ends.
        while (true) {
            cards = withContext(Dispatchers.IO) { weekCards(ctx, reply) }
            if (!writing) break
            delay(1500)
        }
    }
    LargeTitleScaffold("Last week", back = {
        Text("‹ Reports", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs))
    }) {
        item { Text(weekRange(reply), style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.xs)) }
        cards.forEach { card -> item(key = card.kind) { ReportCardView(card, writing = writing) } }
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
    var date by remember { mutableStateOf(LocalDate.now()) }
    val isToday = date == LocalDate.now()
    val scope = rememberCoroutineScope()
    // DT4: the timeline's events (`/timeline`) and reports.json (update entries, the morning note's time).
    var events by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var saved by remember { mutableStateOf(JsonObject(emptyMap())) }
    var updating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // LW5: the last finished week (its Last week card) and the week page's start while it is open.
    var week by remember { mutableStateOf<JsonObject?>(null) }
    var weekList by remember { mutableStateOf<List<ReportCard>>(emptyList()) }
    var openWeek by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        week = Api.reportWeek(ctx)
        weekList = withContext(Dispatchers.IO) { weekCards(ctx, week) }
    }
    suspend fun loadDay(d: LocalDate) {
        val ev = (Api.timeline(ctx, d)?.get("events") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val s = withContext(Dispatchers.IO) { if (reportsOff(ctx)) JsonObject(emptyMap()) else savedReports(ctx) }
        if (date == d) { events = ev; saved = s }
    }
    // "Update me": a ring sync, then one update note; a quiet line when there is nothing new or no answer.
    fun update() = scope.launch {
        updating = true
        status = null
        try {
            syncAndWait(ctx)
            val r = withContext(Dispatchers.Default) { runCatching { writeUpdate(ctx) }.getOrNull() }
            val day = LocalDate.now()
            loadDay(day)
            status = when {
                r == null -> "Could not update just now."
                (r["nothing_new"] as? JsonPrimitive)?.content == "true" -> "Nothing new since " + clock(updateSince(saved, day.toString()).toDouble())
                else -> null
            }
        } finally { updating = false }
    }
    // Writes today's notes, re-reading reports.json as they land; a model that fails to load ends it like a finished write.
    suspend fun write(r: JsonElement, force: Boolean) {
        writing = true
        try {
            // Only while today is shown: the user may have stepped to an earlier day meanwhile.
            suspend fun reread() {
                val l = withContext(Dispatchers.IO) { reportCards(ctx, r) }
                val s = withContext(Dispatchers.IO) { savedReports(ctx) }
                weekList = withContext(Dispatchers.IO) { weekCards(ctx, week) }
                if (date == LocalDate.now()) { cards = l; saved = s }
            }
            coroutineScope {
                val job = launch(Dispatchers.Default) {
                    runCatching { writeReports(ctx, force) }
                    runCatching { writeWeek(ctx, force) }
                }
                while (job.isActive) {
                    delay(1500)
                    reread()
                }
            }
            reread()
        } finally { writing = false }
    }
    LaunchedEffect(date) {
        cards = null
        loaded = false
        events = emptyList()
        saved = JsonObject(emptyMap())
        status = null
        val r = loadReport(ctx, date)
        off = withContext(Dispatchers.IO) { reportsOff(ctx) }
        report = r?.first
        cards = r?.second
        loaded = true
        loadDay(date)
        // Today only, once the night is in: the worker may have been killed before it wrote the missing notes. Earlier days never write.
        if (r != null && isToday && !off && r.second.any { it.kind == "night" } && r.second.any { it.pending }) write(r.first, false)
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
                    weekList = weekList.map { it.copy(written = null, pending = true) }
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
    openWeek?.let { return WeekPage(it, writing) { openWeek = null } }
    val weekWriting = writing
    val lastWeek: LazyListScope.() -> Unit = {
        weekList.firstOrNull { it.kind == "week_overview" }?.let { o ->
            item(key = "last_week") { LastWeekCard(o, weekRange(week), weekWriting) { openWeek = o.date.toString() } }
        }
    }
    LargeTitleScaffold("Reports", back = back, action = regenerate.takeIf { isToday && !off && !cards.isNullOrEmpty() }) {
        item { DayPicker(date) { date = it } }
        val list = cards
        if (list.isNullOrEmpty()) {
            if (loaded) item {
                InsetCard {
                    EmptyState(Icons.Rounded.AutoAwesome, if (isToday) "No report yet" else "No report for this day",
                        if (list == null) "It appears once the server can be reached." else if (isToday) "It fills in after the next sync." else "The ring has too little from this day.")
                }
            }
            lastWeek()
            return@LargeTitleScaffold
        }
        val writing = writing && isToday
        val hero = list.first()
        if (hero.kind != "today") item(key = hero.kind) { ReportCardView(hero, Modifier.hero(), writing = writing) }
        else item(key = hero.kind) {
            val day = date.toString()
            val morningAt = (saved["$day/today"] as? JsonObject)?.get("at").num()?.toLong()
            // Event rows and update entries by time, newest last; while updating, an entry with no text holds the loading bars.
            // TL1: the morning node stays on top; the rail runs from the day's first wake (every event when there is none), never hiding an action.
            val wake = events.filter { (it["kind"] as? JsonPrimitive)?.content == "wake" }.minOfOrNull { it.t() } ?: 0.0
            val entries: List<Pair<Double, Any>> = (halfHours(events.filter { it.t() >= wake }).map { it.first().t() to it } + updates(saved, day).map { (it["at"].num() ?: 0.0) to it })
                .sortedBy { it.first } + if (updating && isToday) listOf(0.0 to JsonObject(emptyMap())) else emptyList()
            val pill = isToday && !off
            ReportCardView(hero, Modifier.hero(), writing = writing, rail = true, morningAt = morningAt, after = (@Composable { a: Color ->
                entries.forEachIndexed { i, (_, e) ->
                    val last = !pill && i == entries.lastIndex
                    // DT5: the automatic evening entry is an update with a moon in sleepLight
                    if (e is JsonObject) { val ev = e.isEvening(); val n = if (ev) c.sleepLight else a
                        RailRow(a, false, last, 28.dp, 0.dp, dot = {
                            Box(Modifier.size(28.dp).background(n.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(if (ev) Icons.Rounded.NightsStay else Icons.Rounded.Update, null, tint = n, modifier = Modifier.size(16.dp))
                            }
                        }) { UpdateEntry(e, n, if (ev) "Evening" else "Update") } }
                    else RailRow(a, false, last, 8.dp, 10.dp, dot = { Box(Modifier.size(8.dp).background(a.copy(alpha = 0.55f), CircleShape)) }) {
                        @Suppress("UNCHECKED_CAST") EventRow(e as List<JsonObject>)
                    }
                }
                if (pill) RailRow(a, false, true, 10.dp, 17.dp, dot = { NowDot(a) }) {
                    Column {
                        val busy = updating || writing
                        Row(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (busy) a.copy(alpha = 0.55f) else a)
                                .clickable(enabled = !busy, role = Role.Button) { haptic.tick(); update() }.padding(horizontal = AppSpace.xl),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = onTint(), modifier = Modifier.size(18.dp))
                            Text(if (updating) "Updating…" else "Update me", style = AppType.headline, color = onTint(), modifier = Modifier.padding(start = AppSpace.s))
                        }
                        status?.let { Text(it, style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.s)) }
                    }
                }
            }).takeIf { entries.isNotEmpty() || pill })
        }
        // DT5: the Tonight card leaves the page once the day has its evening entry.
        val evening = updates(saved, date.toString()).any { it.isEvening() }
        val rest = list.drop(1).filter { !(evening && it.kind == "tonight") }
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
        lastWeek()
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
