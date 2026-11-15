package com.nsheremet.circa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** The running Breathe session on a small screen (360 x 640 dp), with and without pulse readings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BreatheWaveTest {
    @get:Rule val rule = createComposeRule()

    // Synthetic: two beats a second at 25.1 readings a second, a main wave plus a smaller second hump.
    private fun beat(n: Int) = List(n) { i ->
        val p = 2 * PI * 2.0 * i / 25.1
        (70_000 + 3_000 * sin(p) + 1_000 * sin(2 * p + 1)).toInt()
    }

    private fun show(dark: Boolean, light: List<Int>): View {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            AppTheme(dark) {
                LargeTitleScaffold("Breathe", back = { Text("‹ Today") }) {
                    item { BreatheRunning("Hold", 5, 1f, 0.3f, 2, 5, listOf(0L to 64, 60_000L to 61), null, light) }
                }
            }
        }
        // Orb (its phase label) and the phase bar's last label both end inside the 640 dp window.
        for (text in listOf("Hold", "Out 8 s")) {
            val b = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
            assertTrue("$text ends at ${b.bottom}", b.bottom <= 640.dp)
        }
        return view
    }

    @Test fun noReadingsNoWave() {
        show(false, emptyList())
        rule.onNodeWithContentDescription("Pulse wave").assertDoesNotExist()
    }

    @Test fun light() = render(false)
    @Test fun dark() = render(true)

    // Saved for a look, not compared: build/reports/pulse-wave/breathe-<light|dark>.png
    private fun render(dark: Boolean) {
        val view = show(dark, beat(200))
        rule.onNodeWithContentDescription("Pulse wave").assertExists()
        val out = File("build/reports/pulse-wave/breathe-${if (dark) "dark" else "light"}.png").absoluteFile
        out.parentFile!!.mkdirs()
        // captureToImage waits for a frame Robolectric never draws; draw the view into a bitmap instead
        val bmp = rule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
