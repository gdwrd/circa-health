package dev.ring.health.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
)

private val SleepIndigo = Color(0xFF5E5CE6)
private val ReadinessGreen = Color(0xFF30D158)
private val ActivityPink = Color(0xFFFF375F)
private val StageAwake = Color(0xFFFF9F0A)
private val StageRem = Color(0xFF64D2FF)
private val StageCore = Color(0xFF0A84FF)
private val HeartRed = Color(0xFFFF2D55)
private val HrvCyan = Color(0xFF64D2FF)

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
    heart = HeartRed, hrv = HrvCyan,
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
    heart = HeartRed, hrv = HrvCyan,
)

object AppType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val tab = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
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

object AppTheme {
    val colors: AppColors @Composable get() = LocalColors.current
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
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        CompositionLocalProvider(
            LocalColors provides c,
            LocalContentColor provides c.label,
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

/** White (light) / #1C1C1E (dark) rounded group; no elevation. */
@Composable
fun InsetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(AppShape.card).background(AppTheme.colors.card),
        content = content,
    )
}

/** Activity-style ring: faint track, round-cap arc that springs to [progress] (0..1). */
@Composable
fun ScoreRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier.size(96.dp),
    stroke: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        p.animateTo(progress.coerceIn(0f, 1f), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val topLeft = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, topLeft, arc, style = Stroke(w))
            if (p.value > 0f) {
                drawArc(color, -90f, 360f * p.value, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** iOS large-title screen: 34 sp bold title that collapses into a translucent 44 dp bar on scroll. */
@Composable
fun LargeTitleScaffold(title: String, content: LazyListScope.() -> Unit) {
    val c = AppTheme.colors
    val state = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 60 }
    }
    val barAlpha by animateFloatAsState(
        if (collapsed) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "bar",
    )
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = AppSpace.l, end = AppSpace.l, top = statusTop + AppSpace.s, bottom = AppSpace.xl),
            verticalArrangement = Arrangement.spacedBy(AppSpace.xl),
        ) {
            item {
                Text(title, style = AppType.largeTitle, modifier = Modifier.padding(top = AppSpace.xl, start = AppSpace.xs))
            }
            content()
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .graphicsLayer { alpha = barAlpha }.background(c.tabBar).statusBarsPadding().height(44.dp),
        ) {
            Text(title, style = AppType.headline, modifier = Modifier.align(Alignment.Center), textAlign = TextAlign.Center)
            Hairline(Modifier.align(Alignment.BottomCenter))
        }
    }
}
