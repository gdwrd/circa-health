package com.nsheremet.circa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
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
class HeartWaveTest {
    @get:Rule val rule = createComposeRule()

    // Synthetic: two beats a second at 25.1 readings a second.
    private fun beat(n: Int) = List(n) { i ->
        val p = 2 * PI * 2.0 * i / 25.1
        (70_000 + 3_000 * sin(p) + 1_000 * sin(2 * p + 1)).toInt()
    }

    private lateinit var view: View

    private fun show(light: List<Int>, dark: Boolean = false) = rule.setContent {
        view = LocalView.current
        AppTheme(dark) {
            val c = AppTheme.colors
            Box(Modifier.width(360.dp).background(c.background).padding(AppSpace.l)) {
                Box(Modifier.fillMaxWidth().clip(AppShape.card).background(c.card).padding(AppSpace.l)) {
                    MeasureRing(0.4f, 1f, 64, light)
                }
            }
        }
    }

    @Test fun noWaveBeforeReadings() {
        show(emptyList())
        rule.onNodeWithContentDescription("Pulse wave").assertDoesNotExist()
    }

    @Test fun light() = render(false)
    @Test fun dark() = render(true)

    // Saved for a look, not compared: build/reports/pulse-wave/measure-<light|dark>.png
    private fun render(dark: Boolean) {
        show(beat(200), dark)
        rule.onNodeWithContentDescription("Pulse wave").assertExists()
        val out = File("build/reports/pulse-wave/measure-${if (dark) "dark" else "light"}.png").absoluteFile
        out.parentFile!!.mkdirs()
        // captureToImage waits for a frame Robolectric never draws; draw the view into a bitmap instead
        val bmp = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
