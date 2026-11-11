package com.nsheremet.circa.ui

import android.graphics.BlurMaskFilter
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.haze
import com.nsheremet.circa.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One-file design system: iOS-style palette, type scale, shapes, spacing and the shared composables. */

@Immutable
data class AppColors(
    val background: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val separator: Color,
    val tint: Color,
    val tabBar: Color,
    val sleep: Color,
    val readiness: Color,
    val activity: Color,
    val stageAwake: Color,
    val stageRem: Color,
    val stageCore: Color,
    val stageDeep: Color,
    val heart: Color,
    val hrv: Color,
    /** Breathe screen (iOS system mint). */
    val breathe: Color,
    /** Lighter gradient partner of [breathe] (orb centre, ring head, phase bar end). */
    val breatheLight: Color,
    val zoneRestored: Color,
    val zoneRelaxed: Color,
    val zoneEngaged: Color,
    val zoneStressed: Color,
    val cardStroke: Color,
    val sleepLight: Color = SleepLight,
    val readinessLight: Color = ReadinessLight,
    val activityLight: Color = ActivityLight,
    val heartLight: Color = HeartLight,
    /** Alpha of the blurred glow under large rings. */
    val glow: Float = 0.35f,
    val moodGood: Color = ReadinessGreen.copy(alpha = 0.1f),
    val moodFair: Color = ZoneEngaged.copy(alpha = 0.1f),
    val moodLow: Color = ActivityPink.copy(alpha = 0.1f),
    val signalCalm: Color = ReadinessGreen,
    val signalElevated: Color = ZoneEngaged,
    val signalHigh: Color = StageAwake,
    /** Sleep "Time asleep" band: deep indigo fading to black (dark) / to nothing (light), both at 12 %. */
    val nightTop: Color = SleepIndigo.copy(alpha = 0.12f),
    val nightBottom: Color = Color.Transparent,
    /** Good / bad change text (insight pills, medication Change); darker in light mode for 4.5:1 on white. */
    val goodText: Color = ReadinessGreen,
    val badText: Color = StageAwake,
    /** Unfilled capsule segment and the regularity median band; stronger in dark mode. */
    val emptySegment: Color = Color(0x298E8E93),
    val sleepBand: Color = SleepIndigo.copy(alpha = 0.12f),
    /** Launcher icon background (res ic_launcher bg), for the mark shown as the app icon. */
    val brandTile: Color = Color(0xFF0B0B10),
) {
    /** Lighter gradient partner of a score colour (the arc head); other colours pair with themselves. */
    fun light(c: Color): Color = when (c) {
        sleep -> sleepLight
        readiness -> readinessLight
        activity -> activityLight
        heart -> heartLight
        breathe -> breatheLight
        else -> c
    }
}

private val SleepIndigo = Color(0xFF5E5CE6)
private val ReadinessGreen = Color(0xFF30D158)
private val ActivityPink = Color(0xFFFF375F)
private val StageAwake = Color(0xFFFF9F0A)
private val StageRem = Color(0xFF64D2FF)
private val StageCore = Color(0xFF0A84FF)
private val HeartRed = Color(0xFFFF2D55)
private val HrvCyan = Color(0xFF64D2FF)
private val ZoneEngaged = Color(0xFFFFD60A)
private val SleepLight = Color(0xFF8E8CFF)
private val ReadinessLight = Color(0xFF7CF2A2)
private val ActivityLight = Color(0xFFFF7A9A)
private val HeartLight = Color(0xFFFF6B8A)

private val LightColors = AppColors(
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF8E8E93),
    separator = Color(0x4A3C3C43),
    tint = Color(0xFF007AFF),
    tabBar = Color(0xE6F9F9F9),
    sleep = SleepIndigo, readiness = ReadinessGreen, activity = ActivityPink,
    stageAwake = StageAwake, stageRem = StageRem, stageCore = StageCore, stageDeep = SleepIndigo,
    heart = HeartRed, hrv = HrvCyan, breathe = Color(0xFF00C7BE), breatheLight = Color(0xFF5CE1DA),
    zoneRestored = ReadinessGreen, zoneRelaxed = StageRem, zoneEngaged = ZoneEngaged, zoneStressed = StageAwake,
    cardStroke = Color(0x0A000000),
    goodText = Color(0xFF1F7A35), badText = Color(0xFFC93400),
)

private val DarkColors = AppColors(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF8E8E93),
    separator = Color(0x99545458),
    tint = Color(0xFF0A84FF),
    tabBar = Color(0xE6161618),
    sleep = SleepIndigo, readiness = ReadinessGreen, activity = ActivityPink,
    stageAwake = StageAwake, stageRem = StageRem, stageCore = StageCore, stageDeep = SleepIndigo,
    heart = HeartRed, hrv = HrvCyan, breathe = Color(0xFF63E6E2), breatheLight = Color(0xFFB0F5F2),
    zoneRestored = ReadinessGreen, zoneRelaxed = StageRem, zoneEngaged = ZoneEngaged, zoneStressed = StageAwake,
    cardStroke = Color(0x0FFFFFFF),
    nightBottom = Color(0x1F000000),
    emptySegment = Color(0x598E8E93), sleepBand = SleepIndigo.copy(alpha = 0.3f),
)

/** Bundled OFL Nunito: rounded numerals and titles; body text stays on the platform sans. */
private val Nunito = FontFamily(Font(R.font.nunito_bold, FontWeight.Bold), Font(R.font.nunito_extrabold, FontWeight.ExtraBold))

object AppType {
    val display = TextStyle(fontFamily = Nunito, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
    val largeTitle = TextStyle(fontFamily = Nunito, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    val title = TextStyle(fontFamily = Nunito, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
}

// shortcut: read when AppTheme composes; a 12/24 h switch shows on the next app start, fine for a rarely changed setting.
private var is24h = false

/** Clock pattern in the device's 12/24 h setting. */
fun clockFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault())

/** Clock time of an epoch second. */
fun clock(epochS: Double): String = Instant.ofEpochSecond(epochS.toLong()).atZone(ZoneId.systemDefault()).format(clockFormat())

/** A LocalTime or a stored "HH:MM" (journal medication) in the device format; unparsable text is returned as is. */
fun clock(t: LocalTime): String = t.format(clockFormat())
fun clock(hhmm: String): String = runCatching { clock(LocalTime.parse(hhmm)) }.getOrDefault(hhmm)

/** "7 h 15 min", "1 h" when the minutes are 0, "45 min" under an hour. */
fun duration(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

object AppShape {
    val card = RoundedCornerShape(14.dp)
    val small = RoundedCornerShape(10.dp)
}

object AppSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
}

private val LocalColors = staticCompositionLocalOf { LightColors }

/** True when the system animator scale is 0 (Settings > Accessibility > Remove animations). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Light selection tick for selection changes and measurement lock. */
fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

/** Height of the tab bar the content scrolls under (App provides it). */
val LocalBottomBar = compositionLocalOf { 0.dp }

/** Opens Settings (App provides it); LargeTitleScaffold puts a gear on every screen without a back link. */
val LocalOpenSettings = compositionLocalOf<(() -> Unit)?> { null }

/** Opens Breathe (App provides it, only with the backend); LargeTitleScaffold puts a Breathe button on every screen without a back link. */
val LocalOpenBreathe = compositionLocalOf<(() -> Unit)?> { null }

object AppTheme {
    val colors: AppColors @Composable get() = LocalColors.current
    /** Blurred bar material: background blur under a lighter tabBar tint (haze falls back to the tint alone). */
    val bar: HazeStyle @Composable get() = colors.let { HazeStyle(it.background, HazeTint(it.tabBar.copy(alpha = 0.7f)), blurRadius = 24.dp, noiseFactor = 0f) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.tint, background = c.background, surface = c.card, onBackground = c.label, onSurface = c.label)
    } else {
        lightColorScheme(primary = c.tint, background = c.background, surface = c.card, onBackground = c.label, onSurface = c.label)
    }
    val type = Typography(
        headlineLarge = AppType.largeTitle, titleLarge = AppType.title, titleMedium = AppType.headline,
        bodyLarge = AppType.body, bodyMedium = AppType.subhead, bodySmall = AppType.footnote,
        labelSmall = AppType.tab,
    )
    val shapes = Shapes(small = AppShape.small, medium = AppShape.card, large = AppShape.card)
    val ctx = LocalContext.current
    is24h = DateFormat.is24HourFormat(ctx)
    val resolver = ctx.contentResolver
    val reduceMotion = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        CompositionLocalProvider(
            LocalColors provides c,
            LocalContentColor provides c.label,
            LocalReduceMotion provides reduceMotion,
            LocalRippleConfiguration provides RippleConfiguration(
                color = c.secondaryLabel,
                rippleAlpha = RippleAlpha(draggedAlpha = 0.1f, focusedAlpha = 0.08f, hoveredAlpha = 0.04f, pressedAlpha = 0.1f),
            ),
            content = content,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(AppTheme.colors.separator))
}

/** White (light) / #1C1C1E (dark) rounded group with a 1 dp hairline stroke; no elevation. */
@Composable
fun InsetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(AppShape.card).background(AppTheme.colors.card)
            .border(1.dp, AppTheme.colors.cardStroke, AppShape.card),
        content = content,
    )
}

/** Activity-style ring: faint track, sweep-gradient arc (light end at the head) that springs to [progress] (0..1), or starts there when ![animate]; blurred glow when >= 120 dp. [alpha] fades the whole ring (0.6 = low confidence). [mark] (0..1) puts a hollow dot on the track. */
@Composable
fun ScoreRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier.size(96.dp),
    stroke: Dp = 12.dp,
    animate: Boolean = true,
    alpha: Float = 1f,
    mark: Float? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p = remember { Animatable(if (animate) 0f else progress.coerceIn(0f, 1f)) }
    LaunchedEffect(progress) {
        p.animateTo(progress.coerceIn(0f, 1f), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow))
    }
    val colors = AppTheme.colors
    val light = colors.light(color)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val topLeft = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(color.copy(alpha = 0.12f * alpha), 0f, 360f, false, topLeft, arc, style = Stroke(w))
            val v = p.value
            if (v > 0f) rotate(-90f) {
                val sweep = 360f * v
                if (size.minDimension >= 120.dp.toPx()) drawIntoCanvas { canvas ->
                    val glow = Paint().asFrameworkPaint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = w
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        this.color = color.copy(alpha = colors.glow * alpha).toArgb()
                        maskFilter = BlurMaskFilter(w * 1.2f, BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawArc(topLeft.x, topLeft.y, topLeft.x + arc.width, topLeft.y + arc.height, 0f, sweep, false, glow)
                }
                // Gradient returns to the base colour after the head so the start cap does not pick up the light end;
                // a full ring peaks halfway and ends on the base colour so 12 o'clock has no seam.
                val stops = if (v < 0.999f) arrayOf(0f to color, v to light, 1f to color) else arrayOf(0f to color, 0.5f to light, 1f to color)
                drawArc(Brush.sweepGradient(*stops, center = center), 0f, sweep, false, topLeft, arc, alpha = alpha, style = Stroke(w, cap = StrokeCap.Round))
            }
            // Hollow dot on the track at [mark] (Guess first): a card-coloured hole, so it reads on the arc and on the bare track.
            if (mark != null) rotate(360f * mark.coerceIn(0f, 1f)) {
                val o = Offset(center.x, w / 2)
                drawCircle(colors.card, w * 0.36f, o)
                drawCircle(color, w * 0.29f, o, style = Stroke(w * 0.14f))
            }
        }
        content()
    }
}

/** iOS large-title screen: 34 sp bold title that collapses into a blurred 44 dp bar on scroll; pull to refresh when [onRefresh] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    back: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val open = LocalOpenSettings.current.takeIf { back == null }
    val breathe = LocalOpenBreathe.current.takeIf { back == null }
    val action: (@Composable () -> Unit)? = if (open == null && breathe == null) action else ({
        Row(verticalAlignment = Alignment.CenterVertically) {
            action?.invoke()
            breathe?.let { b ->
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { haptic.tick(); b() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SelfImprovement, "Breathe", tint = c.tint, modifier = Modifier.size(24.dp))
                }
            }
            open?.let { o ->
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { haptic.tick(); o() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SettingsIcon, "Settings", tint = c.tint, modifier = Modifier.size(24.dp))
                }
            }
        }
    })
    val state = rememberLazyListState()
    val haze = remember { HazeState() }
    val collapsed by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 60 }
    }
    val barAlpha by animateFloatAsState(
        if (collapsed) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "bar",
    )
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val list = @Composable {
        LazyColumn(
            Modifier.fillMaxSize().haze(haze),
            state = state,
            contentPadding = PaddingValues(start = AppSpace.l, end = AppSpace.l, top = statusTop + AppSpace.s, bottom = AppSpace.xl + LocalBottomBar.current),
            verticalArrangement = Arrangement.spacedBy(AppSpace.xl),
        ) {
            item {
                Column(Modifier.padding(top = AppSpace.xl)) {
                    back?.invoke()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = AppType.largeTitle, modifier = Modifier.weight(1f).padding(start = AppSpace.xs))
                        action?.invoke()
                    }
                    Api.stale.value?.let { ms ->
                        val now = System.currentTimeMillis()
                        val ago = if (now - ms < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS)
                        Text("Showing data from $ago · server unreachable", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.xs, top = AppSpace.xs))
                        Hairline(Modifier.padding(top = AppSpace.s))
                    }
                }
            }
            content()
        }
    }
    Box(Modifier.fillMaxSize().background(c.background)) {
        if (onRefresh == null) list() else {
            val pull = rememberPullToRefreshState()
            PullToRefreshBox(
                refreshing, onRefresh, Modifier.fillMaxSize(), state = pull,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        pull, refreshing, Modifier.align(Alignment.TopCenter).padding(top = statusTop), containerColor = c.card, color = c.tint,
                    )
                },
            ) { list() }
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .graphicsLayer { alpha = barAlpha }.hazeChild(haze, AppTheme.bar).statusBarsPadding().height(44.dp),
        ) {
            Text(title, style = AppType.headline, modifier = Modifier.align(Alignment.Center), textAlign = TextAlign.Center)
            back?.let { Box(Modifier.align(Alignment.CenterStart).padding(start = AppSpace.l)) { it() } }
            action?.let { Box(Modifier.align(Alignment.CenterEnd).padding(end = AppSpace.l)) { it() } }
            Hairline(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Integer that counts up from 0 to [value] in 600 ms (ease-out); starts there under reduce-motion or when ![animate]. */
@Composable
fun CountUp(value: Int, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, animate: Boolean = true) {
    val reduce = LocalReduceMotion.current
    val n = remember { Animatable(if (reduce || !animate) value.toFloat() else 0f) }
    LaunchedEffect(value) {
        if (reduce) n.snapTo(value.toFloat()) else n.animateTo(value.toFloat(), tween(600, easing = EaseOut))
    }
    Text(n.value.roundToInt().toString(), modifier, color = color, style = style)
}

/** No-data content for a card: 56 dp rounded icon tile on secondaryLabel 12 %, a title and one line, centred. */
@Composable
fun EmptyState(icon: ImageVector, title: String, line: String) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AppSpace.xl, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(c.secondaryLabel.copy(alpha = 0.12f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.secondaryLabel, modifier = Modifier.size(28.dp))
        }
        Text(title, style = AppType.headline, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AppSpace.m))
        Text(line, style = AppType.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AppSpace.xs))
    }
}

/** Tab without a backend: large title and the "Scores need the backend" empty state (blank while the probe runs). */
@Composable
fun NoBackendTab(title: String, backend: Boolean?, action: (@Composable () -> Unit)? = null) = LargeTitleScaffold(title, action = action) {
    if (backend == false) item { InsetCard { EmptyState(Icons.Rounded.CloudOff, "Scores need the backend", "Set the server URL and token in Settings.") } }
}

fun dayLabel(date: LocalDate): String = LocalDate.now().let { today ->
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
}

/** ‹ label ›: "Today", "Yesterday" or "Tue 5 Nov"; › is dead at today, ‹ at the 90-day floor. */
@Composable
fun DayPicker(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val today = LocalDate.now()
    @Composable
    fun Chevron(icon: ImageVector, name: String, to: LocalDate, enabled: Boolean) =
        Box(
            Modifier.size(44.dp).clip(AppShape.small).clickable(enabled = enabled) { haptic.tick(); onChange(to) },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, name, tint = if (enabled) c.tint else c.secondaryLabel) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Chevron(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day", date.minusDays(1), date.isAfter(today.minusDays(90)))
        Text(dayLabel(date), style = AppType.headline, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Chevron(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day", date.plusDays(1), date.isBefore(today))
    }
}

/** Card with a title above it (Heart, Activity). */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
        Text(title, style = AppType.title, modifier = Modifier.padding(start = AppSpace.xs))
        InsetCard(content = content)
    }
}

@Composable
internal fun Value(v: Double, unit: String, color: Color) {
    Text(
        buildAnnotatedString {
            append(v.roundToInt().toString())
            withStyle(SpanStyle(fontSize = 17.sp, color = AppTheme.colors.secondaryLabel)) { append(" $unit") }
        },
        style = AppType.largeTitle, modifier = Modifier.padding(start = AppSpace.l),
    )
}

/** iOS tinted button: tint text on a faint tint fill. */
@Composable
internal fun TintButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    Text(
        text, style = AppType.headline, color = c.tint, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().clip(AppShape.small).background(c.tint.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(vertical = AppSpace.m),
    )
}

internal class Trend(val first: LocalDate, val value: List<Pair<Double, Double>>, val base: List<Pair<Double, Double>>)

/** `/trends` points as day offsets from the first date. */
internal fun trend(json: JsonElement?): Trend? {
    val pts = ((json as? JsonObject)?.get("points") as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let { o -> (o["date"] as? JsonPrimitive)?.takeIf { it.isString }?.let { LocalDate.parse(it.content) to o } }
    } ?: return null
    val first = pts.firstOrNull()?.first ?: return null
    fun col(k: String) = pts.mapNotNull { (d, o) -> (o[k] as? JsonPrimitive)?.doubleOrNull?.let { (d.toEpochDay() - first.toEpochDay()).toDouble() to it } }
    return Trend(first, col("value"), col("baseline"))
}

@Composable
internal fun TrendCard(title: String, t: Trend?, unit: String, color: Color) {
    val c = AppTheme.colors
    Section(title) {
        if (t == null || t.value.isEmpty()) return@Section EmptyState(Icons.Rounded.MonitorHeart, "Not enough data yet", "Keep the ring on; this fills in after a few syncs.")
        Text(
            "Latest", style = AppType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = AppSpace.l, top = AppSpace.l),
        )
        Value(t.value.last().second, unit, color)
        t.base.lastOrNull()?.let {
            Text(
                "Baseline ${it.second.roundToInt()} $unit", style = AppType.subhead, color = c.secondaryLabel,
                modifier = Modifier.padding(start = AppSpace.l),
            )
        }
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val byX = t.value.toMap()
        Chart(
            listOf(t.value, t.base), color, false, 7, 0,
            marker = { x -> "${byX[x]?.roundToInt()?.let { "$it $unit" } ?: "—"} · ${t.first.plusDays(x.toLong()).format(fmt)}" },
        ) { x -> t.first.plusDays(x.toLong()).format(fmt) }
    }
}

/** [series] 0 is the main line with a faded area fill; series 1 (optional) is a wide translucent band. */
@Composable
internal fun Chart(
    series: List<List<Pair<Double, Double>>>, color: Color, area: Boolean, spacing: Int, offset: Int,
    second: Color? = null, x: ClosedFloatingPointRange<Double>? = null, decorations: List<Decoration> = emptyList(), height: Dp = 180.dp,
    marker: (Double) -> CharSequence, label: (Double) -> String,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            lineSeries { series.filter { it.isNotEmpty() }.forEach { s -> series(s.map { it.first }, s.map { it.second }) } }
        }
    }
    val main = chartLine(color, area)
    val band = if (second != null) chartLine(second) else LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color.copy(alpha = 0.18f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 22f),
    )
    val ys = series.flatten().map { it.second }
    val span = ys.max() - ys.min()
    val step = if (span > 60) 20.0 else if (span > 24) 10.0 else 5.0
    val lo = floor((ys.min() - span * 0.1) / step) * step
    val hi = ceil((ys.max() + span * 0.1) / step) * step
    ScrubChartHost(
        rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(main, band),
                rangeProvider = CartesianLayerRangeProvider.fixed(minX = x?.start, maxX = x?.endInclusive, minY = lo, maxY = maxOf(hi, lo + step)),
            ),
            startAxis = startAxis(step),
            bottomAxis = bottomAxis(CartesianValueFormatter { _, x, _ -> label(x) }, spacing, offset),
            marker = rememberScrubMarker(color, marker),
            decorations = decorations,
        ),
        producer, height,
    )
}

/** Spans shaded in [color] at [alpha] (8 % doses, 12 % workouts), a line at each start when [line]; [windows] are (start, end) in chart x. */
internal class DoseWindows(private val windows: List<Pair<Double, Double>>, private val color: Int, private val alpha: Int = 20, private val line: Boolean = true) : Decoration {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun drawUnderLayers(context: CartesianDrawingContext) = with(context) {
        val b = layerBounds
        fun px(x: Double) = b.left + layerDimensions.startPadding + ((x - ranges.minX) / ranges.xStep).toFloat() * layerDimensions.xSpacing - scroll
        windows.forEach { (dose, end) ->
            paint.color = color
            paint.alpha = alpha
            canvas.drawRect(px(dose).coerceIn(b.left, b.right), b.top, px(end).coerceIn(b.left, b.right), b.bottom, paint)
            if (line && dose >= ranges.minX && dose <= ranges.maxX) {
                paint.alpha = 200
                paint.strokeWidth = dpToPx(1.5f)
                canvas.drawLine(px(dose), b.top, px(dose), b.bottom, paint)
            }
        }
    }
}
