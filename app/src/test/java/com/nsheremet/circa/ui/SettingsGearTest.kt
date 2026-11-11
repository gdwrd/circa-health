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

/** Every tab shows the Settings gear; screens with a back link (Settings, Breathe, sub-screens) do not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsGearTest {
    @get:Rule val rule = createComposeRule()

    private var opened = 0

    private fun show(screen: @Composable () -> Unit) = rule.setContent {
        AppTheme(dark = false) { CompositionLocalProvider(LocalOpenSettings provides { opened++ }) { screen() } }
    }

    // backend = false: each tab renders its no-backend state, no network.
    private fun assertGearOpensSettings(screen: @Composable () -> Unit) {
        show(screen)
        // The gear sits in the large title and in the collapsed bar; tap the first.
        rule.onAllNodesWithContentDescription("Settings").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Settings")[0].performClick()
        assertEquals(1, opened)
    }

    @Test fun today() = assertGearOpensSettings { TodayScreen(false, onBreathe = {}) }
    @Test fun sleep() = assertGearOpensSettings { SleepScreen(false) }
    @Test fun activity() = assertGearOpensSettings { ActivityScreen(false) }
    @Test fun body() = assertGearOpensSettings { BodyScreen(false) }
    @Test fun trends() = assertGearOpensSettings { TrendsScreen(false, false) {} }

    @Test fun noGearWithBackLink() {
        show { LargeTitleScaffold("Settings", back = { Text("‹ Today") }) {} }
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @Test fun noGearWithoutProvider() {
        rule.setContent { AppTheme(dark = false) { LargeTitleScaffold("Today") {} } }
        rule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }
}
