package com.nsheremet.circa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Breathe opens from Stress (under Resilience) and from the "I feel…" reply card; no tab has it in its title bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BreatheButtonTest {
    @get:Rule val rule = createComposeRule()

    private var breatheCalls = 0

    private fun show(screen: @Composable () -> Unit) = rule.setContent {
        AppTheme(dark = false) {
            CompositionLocalProvider(LocalOpenSettings provides {}, LocalOpenBreathe provides { breatheCalls++ }) { screen() }
        }
    }

    // backend = false: each tab renders its no-backend state, no network.
    private fun assertNoBreathe(screen: @Composable () -> Unit) {
        show(screen)
        rule.onNodeWithContentDescription("Breathe").assertDoesNotExist()
        rule.onNodeWithText("Breathe").assertDoesNotExist()
    }

    @Test fun today() = assertNoBreathe { TodayScreen(false, onBreathe = {}) }
    @Test fun sleep() = assertNoBreathe { SleepScreen(false) }
    @Test fun activity() = assertNoBreathe { ActivityScreen(false) }
    @Test fun body() = assertNoBreathe { BodyScreen(false) }
    @Test fun trends() = assertNoBreathe { TrendsScreen(false, false) {} }

    @Test fun stress() {
        show { Resilience(JsonObject(emptyMap())) {} }
        rule.onNodeWithText("Breathe").performClick()
        assertEquals(1, breatheCalls)
    }

    @Test fun replyCard() {
        var replyCalls = 0
        show { NowCard("tired", JsonObject(emptyMap()), {}, { replyCalls++ }) {} }
        rule.onNodeWithText("Breathe").performClick()
        assertEquals(1, replyCalls)
    }

    @Test fun noStressButtonWithoutBackend() {
        rule.setContent { AppTheme(dark = false) { Resilience(JsonObject(emptyMap())) {} } }
        rule.onNodeWithText("Breathe").assertDoesNotExist()
    }
}
