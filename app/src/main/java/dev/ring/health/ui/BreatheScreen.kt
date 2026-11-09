package dev.ring.health.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ring.health.Prefs.breathRounds
import dev.ring.health.Prefs.ringAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val START = 0
private const val RUNNING = 1
private const val FINISHED = 2

/** 4-7-8 phases: name, seconds, circle scale at the end. */
private val PHASES = listOf(Triple("In", 4, 1f), Triple("Hold", 7, 1f), Triple("Out", 8, 0.6f))

internal val FACES = listOf(
    Icons.Rounded.SentimentVeryDissatisfied, Icons.Rounded.SentimentDissatisfied, Icons.Rounded.SentimentNeutral,
    Icons.Rounded.SentimentSatisfied, Icons.Rounded.SentimentVerySatisfied,
)
private val FACE_NAMES = listOf("Very bad", "Bad", "Okay", "Good", "Very good")

private fun mss(s: Long) = "%d:%02d".format(s / 60, s % 60)

/** Guided 4-7-8 breathing with live HR from the ring; Done saves the session to the backend. */
@Composable
fun BreatheScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableIntStateOf(START) }
    var rounds by remember { mutableIntStateOf(ctx.breathRounds) }
    var feelBefore by remember { mutableStateOf<Int?>(null) }
    var feelAfter by remember { mutableStateOf<Int?>(null) }
    var guard by remember { mutableStateOf<String?>(null) }
    var hrNote by remember { mutableStateOf<String?>(null) }
    val samples = remember { mutableStateListOf<Pair<Long, Int>>() }
    var hrJob by remember { mutableStateOf<Job?>(null) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var done by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(1) }
    var phase by remember { mutableStateOf("In") }
    var left by remember { mutableIntStateOf(4) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val circle = remember { Animatable(0.6f) }
    val fill = remember { Animatable(0f) }

    val view = LocalView.current
    DisposableEffect(state == RUNNING) {
        view.keepScreenOn = state == RUNNING
        onDispose { view.keepScreenOn = false }
    }

    fun finish(n: Int) { done = n; endMs = System.currentTimeMillis(); state = FINISHED }

    fun begin() {
        val address = ctx.ringAddress
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        guard = when {
            address.isNullOrBlank() -> "Set the ring address in Settings first."
            !granted -> "Allow Bluetooth for Circa first (Settings > Apps)."
            else -> null
        }
        if (guard != null) return
        samples.clear(); hrNote = null; error = null
        startMs = System.currentTimeMillis()
        round = 1
        state = RUNNING
        hrJob = scope.launch {
            val r = runLiveHr(ctx.applicationContext, address!!, onBpm = { samples += System.currentTimeMillis() to it },
                durationMs = rounds * 19_000L + 15_000, stopWhenStable = false) {}
            if (samples.isEmpty() && isActive) hrNote = r // runLiveHr swallows cancellation; ignore its message then
        }
    }

    fun stop() {
        if (round > 1) return finish(round - 1)
        // Nothing finished yet: discard the session.
        hrJob?.cancel(); samples.clear(); state = START
    }

    LaunchedEffect(state) {
        if (state != RUNNING) return@LaunchedEffect
        circle.snapTo(0.6f)
        for (r in 1..rounds) {
            round = r
            for ((name, secs, target) in PHASES) {
                phase = name
                haptic.tick()
                launch { circle.animateTo(target, tween(secs * 1000, easing = LinearEasing)) }
                launch { fill.snapTo(0f); fill.animateTo(1f, tween(secs * 1000, easing = LinearEasing)) }
                for (s in secs downTo 1) { left = s; delay(1000) }
            }
        }
        finish(rounds)
    }

    LargeTitleScaffold(
        "Breathe",
        back = { Text("‹ Today", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs)) },
    ) {
        when (state) {
            START -> {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    HeroCard {
                        val idle = if (reduce) 1f else rememberInfiniteTransition(label = "idle")
                            .animateFloat(0.96f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "idle").value
                        Orb(idle, box = 224.dp) { Text("4 · 7 · 8", style = AppType.display) }
                        Text("In through the nose 4 s, hold 7 s, out through the mouth 8 s.", style = AppType.subhead, color = c.secondaryLabel, textAlign = TextAlign.Center)
                        PhaseBar()
                        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("What the evidence says", style = AppType.footnote, color = c.tint)
                            AnimatedVisibility(expanded) {
                                Text(
                                    "Slow breathing lowers your heart rate and raises heart rate variability for a few minutes afterwards; " +
                                        "that part is well studied. Whether it helps you sleep is something your own data will show in What affects you.",
                                    style = AppType.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AppSpace.xs),
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                        InsetCard {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                                Text("Rounds", style = AppType.body, modifier = Modifier.weight(1f))
                                Stepper("−", "Fewer rounds", rounds > 3) { rounds--; ctx.breathRounds = rounds; haptic.tick() }
                                Text("$rounds", style = AppType.headline, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = AppSpace.s))
                                Stepper("+", "More rounds", rounds < 12) { rounds++; ctx.breathRounds = rounds; haptic.tick() }
                            }
                        }
                        Text("About ${mss(rounds * 19L)}", style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = AppSpace.l))
                    }
                }
                item { FeelCard("How do you feel?", feelBefore) { feelBefore = it } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        TintButton("Begin", onClick = ::begin)
                        guard?.let { Text(it, style = AppType.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
            RUNNING -> {
                item {
                    HeroCard {
                        // Reduce motion: fade 0.5 -> 1.0 instead of growing.
                        Orb(if (reduce) 1f else circle.value, if (reduce) 0.5f + (circle.value - 0.6f) * 1.25f else 1f) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(phase, style = AppType.title)
                                Text("$left", style = AppType.display)
                            }
                        }
                        PhaseBar(PHASES.indexOfFirst { it.first == phase }, fill.value)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(rounds) { Box(Modifier.size(8.dp).clip(CircleShape).background(if (it < round) c.breathe else c.emptySegment)) }
                        }
                        Text("Round $round of $rounds", style = AppType.subhead, color = c.secondaryLabel)
                        val line = samples.lastOrNull()?.let { "${it.second} bpm · started at ${samples.first().second}" } ?: hrNote ?: "—"
                        Text(line, style = AppType.subhead, color = if (samples.isEmpty() && hrNote != null) c.secondaryLabel else c.heart, textAlign = TextAlign.Center)
                    }
                }
                item {
                    InsetCard {
                        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = ::stop).padding(horizontal = AppSpace.l), contentAlignment = Alignment.CenterStart) {
                            Text("Stop", style = AppType.body, color = c.tint)
                        }
                    }
                }
            }
            else -> {
                item {
                    HeroCard {
                        ScoreRing(1f, c.breathe, Modifier.size(200.dp), stroke = 18.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(mss((endMs - startMs) / 1000), style = AppType.display)
                                Text("$done rounds", style = AppType.subhead, color = c.secondaryLabel)
                            }
                        }
                        if (samples.isNotEmpty()) {
                            Row {
                                CountUp(samples.first().second, AppType.largeTitle, Modifier.alignByBaseline())
                                Text(" → ", style = AppType.title, color = c.secondaryLabel, modifier = Modifier.alignByBaseline())
                                CountUp(samples.last().second, AppType.largeTitle, Modifier.alignByBaseline(), color = c.heart)
                                Text(" bpm", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.alignByBaseline())
                            }
                            val low = samples.minBy { it.second }
                            Text("Lowest ${low.second} · after ${(low.first - startMs) / 1000} s", style = AppType.footnote, color = c.secondaryLabel)
                        }
                        Text("Stress change arrives with your next sync.", style = AppType.footnote, color = c.secondaryLabel)
                    }
                }
                item { FeelCard("And now?", feelAfter) { feelAfter = it } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.s)) {
                        TintButton("Done") {
                            if (saving) return@TintButton
                            saving = true; error = null
                            scope.launch {
                                hrJob?.cancel()
                                val ok = Api.postBreathe(ctx, startMs, endMs, done, feelBefore, feelAfter, samples.toList())
                                saving = false
                                if (ok) onBack() else error = "Could not save. Try again."
                            }
                        }
                        error?.let { Text(it, style = AppType.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

/** Hero group: radial mint tint fading into the card, content centred. */
@Composable
private fun HeroCard(content: @Composable () -> Unit) {
    val c = AppTheme.colors
    InsetCard {
        Column(
            Modifier.fillMaxWidth().background(Brush.radialGradient(listOf(c.breathe.copy(alpha = 0.12f), c.breathe.copy(alpha = 0f)))).padding(AppSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpace.m),
        ) { content() }
    }
}

/** Breathing orb: breatheLight -> breathe radial fill, blurred glow, two faint ripple rings; [scale] 0.6..1 sizes it, [fade] is its alpha. */
@Composable
private fun Orb(scale: Float, fade: Float = 1f, box: Dp = 260.dp, content: @Composable BoxScope.() -> Unit) {
    val c = AppTheme.colors
    // Ripples drift outwards and fade; still under reduce-motion.
    val t = if (LocalReduceMotion.current) 0.25f else rememberInfiniteTransition(label = "ripple")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "ripple").value
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
            // Fixed radius: a smaller Start box lets the faint outer ripples run into the card padding.
            val r = 104.dp.toPx() * scale
            for (k in 0..1) {
                val f = (t + k * 0.5f) % 1f
                drawCircle(c.breathe.copy(alpha = 0.3f * (1 - f)), r * (1.04f + 0.2f * f), style = Stroke(1.5.dp.toPx()))
            }
            drawIntoCanvas {
                val glow = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = c.breathe.copy(alpha = c.glow).toArgb()
                    maskFilter = BlurMaskFilter(r * 0.3f, BlurMaskFilter.Blur.NORMAL)
                }
                it.nativeCanvas.drawCircle(center.x, center.y, r, glow)
            }
            drawCircle(Brush.radialGradient(listOf(c.breatheLight.copy(alpha = 0.6f), c.breathe.copy(alpha = 0.35f)), center - Offset(r * 0.3f, r * 0.3f), r * 1.3f), r)
            drawCircle(c.breathe.copy(alpha = 0.5f), r, style = Stroke(1.dp.toPx()))
        }
        content()
    }
}

/** 4:7:8 capsules; all full on Start, else past phases full, [current] filled to [fill], later ones empty. */
@Composable
private fun PhaseBar(current: Int = -1, fill: Float = 1f) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
        PHASES.forEachIndexed { i, (name, secs) ->
            val f = when { current < 0 || i < current -> 1f; i == current -> fill; else -> 0f }
            Column(Modifier.weight(secs.toFloat()), verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.emptySegment)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(c.breathe, c.breatheLight))))
                }
                Text("$name $secs\u00A0s", style = AppType.footnote, color = if (i == current) c.label else c.secondaryLabel, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Stepper(sign: String, name: String, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(sign, style = AppType.title, color = if (enabled) c.tint else c.secondaryLabel) }
}

/** Five faces, 1..5; tapping the selected one clears it (optional). */
@Composable
private fun FeelCard(title: String, value: Int?, onPick: (Int?) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    InsetCard {
        Text(title, style = AppType.body, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, top = AppSpace.m))
        Row(Modifier.fillMaxWidth().padding(horizontal = AppSpace.s, vertical = AppSpace.s), horizontalArrangement = Arrangement.SpaceEvenly) {
            FACES.forEachIndexed { i, icon ->
                val n = i + 1
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton) { haptic.tick(); onPick(if (value == n) null else n) },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, FACE_NAMES[i], tint = if (value == n) c.breathe else c.secondaryLabel, modifier = Modifier.size(32.dp)) }
            }
        }
    }
}
