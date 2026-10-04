// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.PreviewLines
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RowAppearanceTest {
    private val wallpaper = Color(0xFF123456)

    private fun appearance(
        settings: AppSettings = AppSettings(),
        dark: Boolean = false,
        dynamicInUse: Boolean = false
    ) = rowAppearanceFor(settings, dark, dynamicInUse, wallpaper)

    @Test
    fun `the defaults show two lines of preview and no avatar`() {
        val appearance = appearance()

        assertEquals(2, appearance.previewLines)
        assertEquals(false, appearance.showAvatars)
    }

    @Test
    fun `the settings reach the appearance`() {
        val appearance = appearance(
            AppSettings(previewLines = PreviewLines.FIVE, showAvatars = true)
        )

        assertEquals(5, appearance.previewLines)
        assertEquals(true, appearance.showAvatars)
    }

    @Test
    fun `without dynamic colors the unread dot is the iOS blue of the theme`() {
        assertEquals(IosBlueLight, appearance(dark = false).unreadColor)
        assertEquals(IosBlueDark, appearance(dark = true).unreadColor)
    }

    @Test
    fun `with dynamic colors in use the unread dot follows the theme primary`() {
        assertEquals(wallpaper, appearance(dynamicInUse = true).unreadColor)
        assertEquals(wallpaper, appearance(dark = true, dynamicInUse = true).unreadColor)
    }

    @Test
    fun `the text starts after the leading slot, and after the avatar when it is shown`() {
        assertEquals(32.dp, appearance().textIndent)
        assertEquals(84.dp, appearance(AppSettings(showAvatars = true)).textIndent)
    }
}
