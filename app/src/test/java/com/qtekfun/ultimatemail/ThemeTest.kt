// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.qtekfun.ultimatemail.ui.theme.Blue40
import com.qtekfun.ultimatemail.ui.theme.Blue80
import com.qtekfun.ultimatemail.ui.theme.colorSchemeFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThemeTest {
    private val dynamicLight = lightColorScheme()
    private val dynamicDark = darkColorScheme()

    @Test
    fun `uses the wallpaper colors when dynamic color exists`() {
        assertEquals(dynamicLight, colorSchemeFor(false, dynamicLight, dynamicDark))
        assertEquals(dynamicDark, colorSchemeFor(true, dynamicLight, dynamicDark))
    }

    @Test
    fun `falls back to the palette without dynamic color`() {
        assertEquals(Blue40, colorSchemeFor(false, null, null).primary)
        assertEquals(Blue80, colorSchemeFor(true, null, null).primary)
    }
}
