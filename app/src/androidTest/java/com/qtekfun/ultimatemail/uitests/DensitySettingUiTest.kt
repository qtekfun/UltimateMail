// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatemail.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings (RF-11): changing the display density changes the height of the rows of the side menu.
 * The sizes asserted are the ones of `DisplayDensity.metrics()`: 48dp by default, 52dp
 * comfortable, 38dp compact.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DensitySettingUiTest : UiTestBase() {
    /** A folder row of the side menu. */
    private val archiveRow get() = hasText(text(R.string.folder_archive)) and hasClickAction()

    private fun chooseDensity(option: Int) {
        openDrawer()
        compose.onNode(hasText(text(R.string.drawer_settings)) and hasClickAction()).performClick()
        // The menu also has a "Settings" row, so wait for something only the screen has.
        val densityRow = hasText(text(R.string.settings_density)) and hasClickAction()
        waitFor(densityRow)
        compose.onNode(densityRow).performClick()
        compose.onNode(hasText(text(option)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
    }

    private fun assertDrawerRowsAre(height: Int) {
        openDrawer()
        waitFor(archiveRow)
        compose.onNode(archiveRow).assertHeightIsEqualTo(height.dp)
        pressBack()
    }

    @Test
    fun changingTheDensityResizesTheRowsOfTheSideMenu() {
        seedAccount("ana@example.com", "Ana", listOf("Anything"))
        launchApp()
        assertDrawerRowsAre(DEFAULT_HEIGHT)

        chooseDensity(R.string.density_comfortable)
        assertEquals("COMFORTABLE", settings.getString("density"))
        assertDrawerRowsAre(COMFORTABLE_HEIGHT)

        chooseDensity(R.string.density_compact)
        assertEquals("COMPACT", settings.getString("density"))
        assertDrawerRowsAre(COMPACT_HEIGHT)
    }

    private companion object {
        const val DEFAULT_HEIGHT = 48
        const val COMFORTABLE_HEIGHT = 52
        const val COMPACT_HEIGHT = 38
    }
}
