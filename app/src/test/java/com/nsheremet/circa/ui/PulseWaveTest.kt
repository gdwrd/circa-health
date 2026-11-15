package com.nsheremet.circa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.nsheremet.circa.ColmiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PulseWaveTest {
    @get:Rule val rule = createComposeRule()

    // Synthetic ring: two beats a second of about 3 000 (light dips as blood fills) on a level that drifts 30 000 every
    // 150 readings (6 s) and steps up 23 000 at reading STEP (the sensor changing its light level).
    private val STEP = 120
    private fun ring(n: Int, step: Int = STEP) = List(n) { i ->
        val p = 2 * PI * 2.0 * i / PULSE_RATE
        val level = 100_000 - 30_000 * i / 150 + if (i >= step) 23_000 else 0
        (level - 1_500 * sin(p) - 500 * sin(2 * p + 1)).toInt()
    }

    private fun filtered(r: List<Int>): List<Float> = PulseFilter().let { f -> r.map { f.push(it, false) } }

    private fun span(w: List<Float>) = w.max() - w.min()

    @Test fun beatsSurviveDriftAndStep() {
        val w = filtered(ring(300))
        // 2 s from the start and 2 s after the step: the filter and the ramp-in have settled
        for (a in (50..STEP - 25) + (STEP + 50..w.size - 25)) {
            val s = w.subList(a, a + 25)
            assertTrue("1 s from reading $a spans ${span(s)}", span(s) in 0.8f..2.0f)
            assertTrue("1 s from reading $a has mean ${s.average()}", abs(s.average()) <= 0.25)
        }
    }

    @Test fun constantIsZero() = assertTrue(filtered(List(100) { 71_848 }).all { it == 0f })

    @Test fun fallingReadingRises() {
        val w = filtered(List(100) { 70_000 } + List(5) { 70_000 - 600 * (it + 1) })
        assertTrue(w.last() > 0.1f)
    }

    @Test fun disturbanceDoesNotShrinkBeats() {
        val r = ring(300, step = Int.MAX_VALUE).toMutableList()
        r[150] += 8_000
        val w = filtered(r)
        val before = span(w.subList(100, 150))
        val after = span(w.subList(175, 225))
        assertTrue("beats $before before, $after after", after >= before * 2 / 3)
    }

    @Test fun noWindow() {
        val r = ring(300)
        assertEquals(filtered(r), filtered(r))
        assertEquals(filtered(r).take(200), filtered(r.take(200)))
    }

    private val nominal = PULSE_RATE * 0.016

    // Offers 0.5s at the pulse rate in bursts every 260 ms (6 or 7 at a time), nothing during [quiet] (ms), and advances
    // in 16 ms frames; [frame] sees each frame's time, the playout and how far the head moved.
    private fun play(ms: Int, quiet: IntRange = IntRange.EMPTY, frame: (Int, Playout, Double) -> Unit) {
        val p = Playout()
        var offered = 0L
        var tick = 0
        for (t in 0..ms step 16) {
            while (tick <= t) {
                val due = (PULSE_RATE * tick / 1000).toLong()
                if (tick in quiet) offered = due
                while (offered < due) { p.offer(0.5f); offered++ }
                tick += 260
            }
            val before = p.head
            p.advance(0.016)
            frame(t, p, p.head - before)
        }
    }

    @Test fun burstsPlayAtSteadySpeed() = play(20_000) { t, p, step ->
        if (p.playing) assertTrue("step $step at $t ms", step in 0.9 * nominal - 1e-9..1.3 * nominal + 1e-9)
        assertTrue("${p.queued} queued at $t ms", p.queued <= 25)
        if (t >= 1_000) assertTrue("not playing at $t ms", p.playing)
    }

    @Test fun pauseDrawsBaselineAtSameSpeed() = play(20_000, quiet = 8_000..9_999) { t, p, step ->
        if (p.playing) assertTrue("step $step at $t ms", step in 0.9 * nominal - 1e-9..1.3 * nominal + 1e-9)
        if (t in 8_800..10_000) {
            assertEquals("step at $t ms", nominal, step, 1e-9)
            assertEquals("shown at $t ms", 0f, p.shown.last(), 0f)
        }
        if (t in 13_000..20_000) assertTrue("${p.queued} queued at $t ms", p.queued in 5..18)
    }

    @Test fun light() = render(false)
    @Test fun dark() = render(true)

    // Saved for a look, not compared: build/reports/pulse-wave/<light|dark>.png, after 9 s of readings in bursts
    private fun render(dark: Boolean) {
        lateinit var view: View
        val readings = mutableStateOf(emptyList<Int>())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = LocalView.current
            AppTheme(dark) {
                val c = AppTheme.colors
                Box(Modifier.width(360.dp).background(c.background).padding(AppSpace.l)) {
                    Box(Modifier.fillMaxWidth().clip(AppShape.card).background(c.card).padding(AppSpace.l)) {
                        PulseWave(readings.value, Modifier.fillMaxWidth().height(56.dp))
                    }
                }
            }
        }
        val r = ring(300)
        for (t in 260..9_000 step 260) {
            readings.value = r.take((PULSE_RATE * t / 1000).toInt())
            rule.mainClock.advanceTimeBy(260)
        }
        rule.onNodeWithContentDescription("Pulse wave").assertExists()
        val out = File("build/reports/pulse-wave/${if (dark) "dark" else "light"}.png").absoluteFile
        out.parentFile!!.mkdirs()
        // captureToImage waits for a frame Robolectric never draws; draw the view into a bitmap instead
        val bmp = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Replays a recording (PULSE_CSV=<file with n,arrival_ms,value>) at its real arrival times; every 5 s of recording
    // the strip goes to build/reports/pulse-wave/replay-<seconds>.png. Skipped without PULSE_CSV.
    @Test fun replay() {
        val csv = System.getenv("PULSE_CSV")?.let(::File)
        assumeTrue(csv != null && csv.isFile)
        val rows = csv!!.readLines().drop(1).map { it.split(',') }
            .map { it[1].trim().toLong() to it[2].trim().toInt() }
            .filter { ColmiProtocol.lightTag(it.second) == 1 && ColmiProtocol.lightReading(it.second) < 0x1FFF00 }
        val t0 = rows.first().first
        val f = PulseFilter()
        val p = Playout()
        var k = 0
        var arrived = -1L
        var lo = Double.MAX_VALUE
        var hi = 0.0
        var shot = 5_000L
        var t = 0L
        while (t <= rows.last().first - t0 + 1_000) {
            while (k < rows.size && rows[k].first - t0 <= t) {
                val at = rows[k].first - t0
                p.offer(f.push(ColmiProtocol.lightReading(rows[k].second), arrived >= 0 && at - arrived > PAUSE_NS / 1_000_000))
                arrived = at
                k++
            }
            val before = p.head
            p.advance(0.016)
            if (p.playing) { lo = minOf(lo, p.head - before); hi = maxOf(hi, p.head - before) }
            if (t >= shot) { strip(p, File("build/reports/pulse-wave/replay-${shot / 1000}.png").absoluteFile); shot += 5_000 }
            t += 16
        }
        println("replay: head step per 16 ms frame min $lo max $hi (nominal $nominal)")
    }

    // The strip as the composable draws it, line only, with a grey midline (the baseline) to judge drift against.
    private fun strip(p: Playout, out: File) {
        val w = 1_200; val h = 240; val pad = 8f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawLine(0f, h / 2f, w.toFloat(), h / 2f, Paint().apply { color = Color.LTGRAY })
        val v = p.shown.ifEmpty { listOf(0f) }
        val frac = (p.head - p.head.toLong()).toFloat()
        val dx = (w - pad) / (WINDOW - 1)
        fun x(i: Int) = w - pad - (v.lastIndex - i + frac) * dx
        fun y(a: Float) = h / 2f - a * (h / 2f - pad)
        val path = Path().apply {
            moveTo(x(0), y(v[0]))
            for (i in 1..v.lastIndex) lineTo(x(i), y(v[i]))
            lineTo(w - pad, y(v.last() + (p.next - v.last()) * frac))
        }
        c.drawPath(path, Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true })
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
