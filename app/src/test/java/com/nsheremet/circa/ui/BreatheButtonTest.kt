package com.nsheremet.circa.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every tab shows the Breathe button; screens with a back link (Settings, Breathe, sub-screens) do not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BreatheButtonTest {
    @get:Rule val rule = createComposeRule()

    private var breatheCalls = 0
    private var settingsCalls = 0

    private fun show(screen: @Composable () -> Unit) = rule.setContent {
        AppTheme(dark = false) {
            CompositionLocalProvider(
                LocalOpenSettings provides { settingsCalls++ },
                LocalOpenBreathe provides { breatheCalls++ },
            ) { screen() }
        }
    }

    // backend = false: each tab renders its no-backend state, no network.
    private fun assertBreatheOpens(screen: @Composable () -> Unit) {
        show(screen)
        // The Breathe button sits in the large title and in the collapsed bar; tap the first.
        rule.onAllNodesWithContentDescription("Breathe").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Breathe")[0].performClick()
        assertEquals(1, breatheCalls)
    }

    @Test fun today() = assertBreatheOpens { TodayScreen(false, onBreathe = {}) }
    @Test fun sleep() = assertBreatheOpens { SleepScreen(false) }
    @Test fun activity() = assertBreatheOpens { ActivityScreen(false) }
    @Test fun body() = assertBreatheOpens { BodyScreen(false) }
    @Test fun trends() = assertBreatheOpens { TrendsScreen(false, false) {} }

    @Test fun noBreatheWithBackLink() {
        show { LargeTitleScaffold("Breathe", back = { Text("‹ Today") }) {} }
        rule.onNodeWithContentDescription("Breathe").assertDoesNotExist()
    }

    @Test fun noBreatheWithoutProvider() {
        rule.setContent { AppTheme(dark = false) { LargeTitleScaffold("Today") {} } }
        rule.onNodeWithContentDescription("Breathe").assertDoesNotExist()
    }
}
