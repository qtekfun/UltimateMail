// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.ui.system.BatteryHintViewModel
import com.qtekfun.ultimatemail.ui.system.isIgnoringBatteryOptimizations
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sync needs the battery exemption to run with the screen off: the app asks once after the
 * first account exists, and Settings keeps the same choice. Both only make sense on a device
 * where the app is not exempt yet, so the tests are skipped on one where it is.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BatteryHintUiTest : UiTestBase() {
    private val title get() = hasText(text(R.string.battery_hint_title))

    private fun assumeNotExempt() = assumeFalse(isIgnoringBatteryOptimizations(context))

    @Test
    fun theQuestionIsAskedOnceAfterTheFirstAccountAndNotNowIsFinal() {
        assumeNotExempt()
        settings.putBoolean(BatteryHintViewModel.KEY_DONE, false)
        seedAccount("ana@example.com", "Ana", listOf("Hello"))
        launchApp()

        waitFor(title)
        compose.onNode(hasText(text(R.string.battery_hint_allow)) and hasClickAction())
            .assertIsDisplayed()
        compose.onNode(hasText(text(R.string.battery_hint_later)) and hasClickAction())
            .performClick()

        waitUntilGone(title)
        assertTrue(settings.getBoolean(BatteryHintViewModel.KEY_DONE, false))
    }

    @Test
    fun noQuestionIsAskedWithoutAnAccount() {
        assumeNotExempt()
        settings.putBoolean(BatteryHintViewModel.KEY_DONE, false)
        launchApp()

        // The empty app opens on the add-account screen; the question must not cover it.
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(title).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun settingsShowTheBackgroundSyncSectionWithTheAllowButton() {
        assumeNotExempt()
        seedAccount("ana@example.com", "Ana", listOf("Hello"))
        launchApp()
        openDrawer()
        compose.onNode(hasText(text(R.string.drawer_settings)) and hasClickAction()).performClick()

        val header = hasText(text(R.string.settings_section_background))
        waitFor(header)
        compose.onNode(header).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(text(R.string.background_battery_off))).performScrollTo()
            .assertIsDisplayed()
        compose.onNode(hasText(text(R.string.background_allow)) and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
    }
}
