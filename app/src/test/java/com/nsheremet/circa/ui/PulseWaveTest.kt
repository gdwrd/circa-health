package com.nsheremet.circa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PulseWaveTest {
    @get:Rule val rule = createComposeRule()

    // Synthetic: two beats a second at 25.1 readings a second, a main wave plus a smaller second hump.
    private fun beat(n: Int) = List(n) { i ->
        val p = 2 * PI * 2.0 * i / 25.1
        (70_000 + 3_000 * sin(p) + 1_000 * sin(2 * p + 1)).toInt()
    }

    @Test fun empty() = assertEquals(emptyList<Float>(), pulseWave(emptyList()))

    @Test fun flat() = assertEquals(List(10) { 0.5f }, pulseWave(List(10) { 71848 }))

    @Test fun sineSpansFullHeight() {
        val w = pulseWave(beat(150))
        assertEquals(0f, w.min(), 0f)
        assertEquals(1f, w.max(), 0f)
    }

    @Test fun onlyLast150() {
        val s = beat(200)
        assertEquals(150, pulseWave(s).size)
        assertEquals(pulseWave(s.takeLast(150)), pulseWave(s))
    }

    @Test fun light() = render(false)
    @Test fun dark() = render(true)

    // Saved for a look, not compared: build/reports/pulse-wave/<light|dark>.png
    private fun render(dark: Boolean) {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            AppTheme(dark) {
                val c = AppTheme.colors
                Box(Modifier.width(360.dp).background(c.background).padding(AppSpace.l)) {
                    Box(Modifier.fillMaxWidth().clip(AppShape.card).background(c.card).padding(AppSpace.l)) {
                        PulseWave(beat(200), Modifier.fillMaxWidth().height(56.dp))
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Pulse wave").assertExists()
        val out = File("build/reports/pulse-wave/${if (dark) "dark" else "light"}.png").absoluteFile
        out.parentFile!!.mkdirs()
        // captureToImage waits for a frame Robolectric never draws; draw the view into a bitmap instead
        val bmp = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
