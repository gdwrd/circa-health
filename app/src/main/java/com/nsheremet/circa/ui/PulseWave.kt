package com.nsheremet.circa.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

/** Readings shown: 6 s of the pulse channel at 25.1 a second. */
private const val WINDOW = 150

/**
 * The last [WINDOW] readings, oldest first, scaled to the window's own range: 0 = its lowest, 1 = its highest
 * (the mean drops out of that scaling). A flat window is all 0.5; an empty list stays empty.
 */
internal fun pulseWave(samples: List<Int>): List<Float> {
    val w = samples.takeLast(WINDOW)
    val lo = w.minOrNull() ?: return emptyList()
    val range = (w.max() - lo).toFloat()
    return w.map { if (range == 0f) 0.5f else (it - lo) / range }
}

/**
 * The optical pulse wave: one line, newest at the right, fading out to the left. [samples] are the pulse channel's
 * readings as they arrive (a growing list: each growth slides the line left smoothly instead of jumping).
 */
@Composable
fun PulseWave(samples: List<Int>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val shift = remember { Animatable(0f) } // readings not yet scrolled in, in reading widths
    val seen = remember { mutableIntStateOf(samples.size) }
    LaunchedEffect(samples.size) {
        val added = (samples.size - seen.intValue).coerceIn(0, WINDOW)
        seen.intValue = samples.size
        if (added == 0 || reduceMotion) return@LaunchedEffect
        shift.snapTo((shift.value + added).coerceAtMost(WINDOW.toFloat()))
        // about one packet's time, so a steady stream reads as continuous motion
        shift.animateTo(0f, tween(160, easing = LinearEasing))
    }
    val values = pulseWave(samples).ifEmpty { List(WINDOW) { 0.5f } }
    Canvas(modifier, contentDescription = "Pulse wave") {
        val stroke = 2.dp.toPx()
        val dot = 3.dp.toPx()
        val right = size.width - dot // room for the head
        val dx = right / (WINDOW - 1)
        val s = shift.value
        val last = values.lastIndex
        fun x(i: Float) = right - (last - i - s) * dx
        fun y(v: Float) = dot + (1 - v) * (size.height - 2 * dot)
        // smooth: quadratic curves through the midpoints, each reading a control point
        val path = Path().apply {
            moveTo(x(0f), y(values[0]))
            for (i in 1..last) {
                val px = x(i - 1f); val py = y(values[i - 1])
                quadraticTo(px, py, (px + x(i.toFloat())) / 2, (py + y(values[i])) / 2)
            }
            lineTo(x(last.toFloat()), y(values[last]))
        }
        // the head sits where the line meets the right edge
        val f = (last - s).coerceAtLeast(0f)
        val i0 = f.toInt(); val i1 = minOf(i0 + 1, last)
        val head = Offset(x(f), y(values[i0] + (values[i1] - values[i0]) * (f - i0)))
        val fade = Brush.horizontalGradient(0f to c.heart.copy(alpha = 0f), 0.4f to c.heart, endX = right)
        clipRect(right = right) { drawPath(path, fade, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        drawCircle(c.heart, dot, head)
    }
}
