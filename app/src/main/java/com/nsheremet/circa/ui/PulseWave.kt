package com.nsheremet.circa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pulse channel readings a second. */
internal const val PULSE_RATE = 25.1

/** Values on screen: 6 s. */
internal const val WINDOW = 151

/** Nanoseconds without readings after which the filter starts over (the ring ends a measurement every ~30 s). */
internal const val PAUSE_NS = 700_000_000L

/** RBJ biquad coefficients b0, b1, b2, a1, a2 (a0 divided out), Q 0.7071. */
private fun biquad(fc: Double, highPass: Boolean): DoubleArray {
    val w = 2 * PI * fc / PULSE_RATE
    val a = sin(w) / (2 * 0.7071)
    val c = cos(w)
    val a0 = 1 + a
    val b = if (highPass) doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) else doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
    return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, -2 * c / a0, (1 - a) / a0)
}

private val HP = biquad(0.5, true)
private val LP = biquad(4.0, false)

/**
 * One reading in, one value in -1..1 out, each reading filtered once: band-pass 0.5..4 Hz (the level drifts and steps
 * far more than a beat moves it), flipped (light falls as blood fills the finger, so the upstroke points up), divided
 * by a slow amplitude envelope. Starts over on a jump of more than 10 000 (the sensor changing its light level) or a pause.
 */
internal class PulseFilter {
    private val z = DoubleArray(8) // x1, x2, y1, y2 of the high-pass, then of the low-pass
    private var x0 = 0.0
    private var prev = 0.0
    private var n = -1 // readings since the last restart; -1 before the first
    private var amp = 1500.0

    fun push(reading: Int, pause: Boolean): Float {
        val x = reading.toDouble()
        if (n < 0 || pause || abs(x - prev) > 10_000) { z.fill(0.0); x0 = x; n = 0 }
        prev = x
        val y = -step(LP, 4, step(HP, 0, x - x0))
        n++
        val a = abs(y)
        // rises in ~1 s, falls in ~4 s; frozen while a reading is far outside it (a disturbance), except while settling
        if (a < 4 * amp || n < 25) amp += (if (a > amp) 0.08 else 0.01) * (a - amp)
        amp = maxOf(amp, 300.0)
        return (minOf(1.0, n / 38.0) * y / (1.6 * amp)).coerceIn(-1.0, 1.0).toFloat()
    }

    private fun step(k: DoubleArray, o: Int, x: Double): Double {
        val y = k[0] * x + k[1] * z[o] + k[2] * z[o + 1] - k[3] * z[o + 2] - k[4] * z[o + 3]
        z[o + 1] = z[o]; z[o] = x; z[o + 3] = z[o + 2]; z[o + 2] = y
        return y
    }
}

/**
 * Filtered values wait in a queue and are shown at [PULSE_RATE] a second, about 0.4 s (10 values) behind, so the line
 * moves at one speed although readings arrive in bursts. The speed bends 0.9..1.3 times to hold that delay; with an
 * empty queue the line goes on at speed 1 along the baseline (0).
 */
internal class Playout {
    private val queue = ArrayDeque<Float>()
    /** Values the head has passed, oldest first. */
    val shown = ArrayDeque<Float>()
    /** Values the head has passed since the start, with its fraction towards the next. */
    var head = 0.0
        private set
    val queued get() = queue.size
    /** The value the head is moving towards. */
    val next get() = queue.firstOrNull() ?: 0f
    var playing = false
        private set
    private var waited = -1.0 // seconds since the first value; -1 before it
    private var starved = false

    fun offer(v: Float) {
        // back from an empty queue: keep drawing baseline for the delay, so the line keeps its speed and its lag
        if (starved) { repeat(9) { queue.addLast(0f) }; starved = false }
        queue.addLast(v)
        if (waited < 0) waited = 0.0
    }

    fun advance(seconds: Double) {
        if (!playing) {
            if (waited < 0) return
            waited += seconds
            if (queue.size < 10 && waited < 0.5) return
            playing = true
        }
        val speed = if (queue.isEmpty()) 1.0 else (1 + 0.1 * (queue.size - 10) / 10).coerceIn(0.9, 1.3)
        val to = head + PULSE_RATE * speed * seconds
        while (to.toLong() > head.toLong()) {
            if (queue.isEmpty()) starved = true
            shown.addLast(queue.removeFirstOrNull() ?: 0f)
            if (shown.size > WINDOW) shown.removeFirst()
            head = head.toLong() + 1.0
        }
        head = to
    }
}

/**
 * The optical pulse wave: one line, newest at the right, fading out to the left, scrolling at a steady speed.
 * [samples] are the pulse channel's readings as they arrive (a growing list; a shrunk one starts over).
 */
@Composable
fun PulseWave(samples: List<Int>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val latest by rememberUpdatedState(samples)
    val frame = remember { mutableLongStateOf(0L) }
    val feed = remember { Feed() }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val s = latest
            if (s.size < feed.seen) { feed.filter = PulseFilter(); feed.playout = Playout(); feed.seen = 0 }
            if (s.size > feed.seen) {
                val pause = feed.arrived != 0L && now - feed.arrived > PAUSE_NS
                for (i in feed.seen until s.size) feed.playout.offer(feed.filter.push(s[i], pause && i == feed.seen))
                feed.seen = s.size
                feed.arrived = now
            }
            if (last != 0L) feed.playout.advance((now - last) / 1e9)
            last = now
            frame.longValue = now
        }
    }
    Canvas(modifier, contentDescription = "Pulse wave") {
        frame.longValue // redraw every frame
        val p = feed.playout
        val values = p.shown.ifEmpty { listOf(0f) }
        val f = if (reduceMotion) 0f else (p.head - p.head.toLong()).toFloat()
        val stroke = 2.dp.toPx()
        val dot = 3.dp.toPx()
        val right = size.width - dot // room for the head
        val dx = right / (WINDOW - 1)
        val last = values.lastIndex
        fun x(i: Int) = right - (last - i + f) * dx
        fun y(v: Float) = size.height / 2 - v * (size.height / 2 - dot)
        val head = Offset(right, y(values[last] + (p.next - values[last]) * f))
        // smooth: quadratic curves through the midpoints, each value a control point; from the left edge if short
        val path = Path().apply {
            moveTo(if (values.size < WINDOW) 0f else x(0), y(values[0]))
            for (i in 1..last) {
                val px = x(i - 1); val py = y(values[i - 1])
                quadraticTo(px, py, (px + x(i)) / 2, (py + y(values[i])) / 2)
            }
            quadraticTo(x(last), y(values[last]), head.x, head.y)
        }
        val fade = Brush.horizontalGradient(0f to c.heart.copy(alpha = 0f), 0.4f to c.heart, endX = right)
        clipRect(right = right) { drawPath(path, fade, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        drawCircle(c.heart, dot, head)
    }
}

private class Feed {
    var filter = PulseFilter()
    var playout = Playout()
    var seen = 0
    var arrived = 0L // frame time readings last arrived
}
