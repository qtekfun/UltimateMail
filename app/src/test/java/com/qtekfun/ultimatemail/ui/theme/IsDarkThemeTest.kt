// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsDarkThemeTest {
    private fun settings(theme: ThemeMode) = AppSettings(theme = theme)

    @Test
    fun `the system theme mode follows the system`() {
        assertTrue(isDarkTheme(settings(ThemeMode.SYSTEM), systemDark = true))
        assertFalse(isDarkTheme(settings(ThemeMode.SYSTEM), systemDark = false))
    }

    @Test
    fun `a forced dark app is dark even when the system is light`() {
        // The reported bug: light system, dark app, and the clock and battery vanished.
        assertTrue(isDarkTheme(settings(ThemeMode.DARK), systemDark = false))
    }

    @Test
    fun `a forced light app is light even when the system is dark`() {
        assertFalse(isDarkTheme(settings(ThemeMode.LIGHT), systemDark = true))
    }
}
