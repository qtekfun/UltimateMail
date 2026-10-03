// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.ui.theme.Blue40
import com.qtekfun.ultimatemail.ui.theme.Blue80
import com.qtekfun.ultimatemail.ui.theme.colorSchemeFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ThemeTest {
    private val dynamicLight = lightColorScheme(primary = Color.Red)
    private val dynamicDark = darkColorScheme(primary = Color.Green)
    private val defaults = AppSettings()

    private fun scheme(
        settings: AppSettings = defaults,
        systemDark: Boolean = false,
        light: Boolean = true,
        dark: Boolean = true
    ) = colorSchemeFor(
        settings,
        systemDark,
        dynamicLight.takeIf { light },
        dynamicDark.takeIf { dark }
    )

    @Test
    fun `uses the wallpaper colors when dynamic color exists`() {
        assertEquals(dynamicLight, scheme(systemDark = false))
        assertEquals(dynamicDark, scheme(systemDark = true))
    }

    @Test
    fun `falls back to the palette without dynamic color on the device`() {
        assertEquals(Blue40, scheme(systemDark = false, light = false, dark = false).primary)
        assertEquals(Blue80, scheme(systemDark = true, light = false, dark = false).primary)
    }

    @Test
    fun `turning dynamic color off uses the palette even where the device has it`() {
        val settings = defaults.copy(dynamicColor = false)

        assertEquals(Blue40, scheme(settings, systemDark = false).primary)
        assertEquals(Blue80, scheme(settings, systemDark = true).primary)
    }

    @Test
    fun `light and dark modes ignore what the system uses`() {
        val light = defaults.copy(theme = ThemeMode.LIGHT)
        val dark = defaults.copy(theme = ThemeMode.DARK)

        assertEquals(dynamicLight, scheme(light, systemDark = true))
        assertEquals(dynamicDark, scheme(dark, systemDark = false))
    }

    @Test
    fun `amoled makes the dark theme black and keeps its accent`() {
        val amoled = defaults.copy(theme = ThemeMode.DARK, amoled = true)

        val result = scheme(amoled)

        assertEquals(Color.Black, result.background)
        assertEquals(Color.Black, result.surface)
        assertEquals(Color.Green, result.primary)
        assertNotEquals(Color.Black, result.surfaceContainerHigh)
    }

    @Test
    fun `amoled follows the system dark theme and never darkens the light theme`() {
        val amoled = defaults.copy(amoled = true)

        assertEquals(Color.Black, scheme(amoled, systemDark = true).background)
        assertEquals(dynamicLight, scheme(amoled, systemDark = false))
        assertEquals(dynamicLight, scheme(amoled.copy(theme = ThemeMode.LIGHT), systemDark = true))
    }
}
