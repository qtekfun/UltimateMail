// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** WCAG contrast of the colours the app chooses itself (SPEC section 6, contrast). */
class ContrastTest {
    private fun scheme(dark: Boolean, amoled: Boolean = false): ColorScheme = colorSchemeFor(
        AppSettings(
            theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            dynamicColor = false,
            amoled = amoled
        ),
        systemDark = dark,
        dynamicLight = null,
        dynamicDark = null
    )

    private val light = scheme(dark = false)
    private val dark = scheme(dark = true)
    private val amoled = scheme(dark = true, amoled = true)

    private fun assertText(foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= MIN_TEXT_CONTRAST, "$what is $ratio:1, text needs $MIN_TEXT_CONTRAST")
    }

    @Test
    fun `the ratio is 21 for black on white, 1 for a colour on itself, and symmetric`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.0001f)
        assertEquals(
            contrastRatio(Color.Blue, Color.Yellow),
            contrastRatio(Color.Yellow, Color.Blue),
            0.0001f
        )
        // Grey #767676 on white is the known borderline of 4.5:1.
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), Color.White), 0.01f)
    }

    @Test
    fun `the white initial of every avatar colour is readable`() {
        AvatarPalette.forEachIndexed { index, color ->
            assertText(Color.White, color, "initial on avatar colour $index")
        }
    }

    @Test
    fun `label chip text is readable in the light and the dark palette`() {
        LabelPaletteLight.forEachIndexed { index, chip ->
            assertText(chip.content, chip.container, "light chip $index")
        }
        LabelPaletteDark.forEachIndexed { index, chip ->
            assertText(chip.content, chip.container, "dark chip $index")
        }
    }

    @Test
    fun `the overflow chip and the account marker text are readable on every theme`() {
        listOf(light, dark, amoled).forEach { colors ->
            assertText(colors.onSurfaceVariant, colors.surfaceVariant, "overflow chip")
            assertText(colors.onSurfaceVariant, colors.surface, "marker name and secondary text")
        }
    }

    @Test
    fun `the star is a graphic that reaches 3 to 1 on its surface in every theme`() {
        listOf(light, dark, amoled).forEach { colors ->
            val ratio = contrastRatio(starColorOn(colors.surface), colors.surface)
            assertTrue(ratio >= MIN_GRAPHIC_CONTRAST, "star is $ratio:1")
        }
        // The reason for the second colour: the amber of the dark theme fails on a light one.
        assertTrue(contrastRatio(StarColor, light.surface) < MIN_GRAPHIC_CONTRAST)
    }

    @Test
    fun `the star colour follows the surface and not the system`() {
        assertEquals(StarColorOnLight, starColorOn(Color.White))
        assertEquals(StarColor, starColorOn(Color.Black))
        assertTrue(Color.White.isLight())
        assertFalse(Color.Black.isLight())
    }

    @Test
    fun `unread time, badges and the pending icon are readable on the fallback palettes`() {
        listOf(light, dark, amoled).forEach { colors ->
            val theme = if (colors === light) "light" else "dark"
            assertText(colors.primary, colors.surface, "primary text, $theme")
            assertText(colors.primary, colors.surfaceContainerHigh, "primary on a container")
            assertText(colors.tertiary, colors.surface, "badge text, $theme")
            assertText(colors.secondary, colors.surface, "secondary accent, $theme")
        }
        // The icon of a pending change is a graphic: 3:1.
        listOf(light, dark, amoled).forEach { colors ->
            val ratio = contrastRatio(colors.outline, colors.surface)
            assertTrue(ratio >= MIN_GRAPHIC_CONTRAST, "pending icon is $ratio:1")
        }
    }

    @Test
    fun `text on the black containers of the AMOLED palette stays readable`() {
        listOf(
            amoled.surface,
            amoled.surfaceContainerLow,
            amoled.surfaceContainer,
            amoled.surfaceContainerHigh,
            amoled.surfaceContainerHighest
        ).forEach {
            assertText(amoled.onSurface, it, "onSurface on $it")
            assertText(amoled.onSurfaceVariant, it, "onSurfaceVariant on $it")
        }
    }

    @Test
    fun `the colours of selection and errors keep their text readable`() {
        listOf(light, dark, amoled).forEach { colors ->
            assertText(colors.onSecondaryContainer, colors.secondaryContainer, "selected row")
            assertText(colors.onErrorContainer, colors.errorContainer, "invalid recipient")
            assertText(colors.error, colors.surface, "error text")
            assertText(colors.onPrimary, colors.primary, "check of a selected row")
        }
    }
}
