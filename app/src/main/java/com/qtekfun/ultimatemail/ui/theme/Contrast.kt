// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** WCAG 2.x thresholds: normal text, and large text or graphics such as icons. */
internal const val MIN_TEXT_CONTRAST = 4.5f
internal const val MIN_GRAPHIC_CONTRAST = 3f

/** The WCAG contrast ratio of two opaque colours, from 1 (same) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
}

private const val LUMINANCE_OFFSET = 0.05f

/** Whether the colour is closer to white than to black: how the app tells a light surface. */
internal fun Color.isLight(): Boolean = luminance() > MID_LUMINANCE

private const val MID_LUMINANCE = 0.5f

/**
 * The star: the amber of [StarColor] reads well on a dark surface, but on a light one it is
 * about 2:1, below the 3:1 icons need, so light surfaces get a deeper amber.
 */
internal fun starColorOn(surface: Color): Color =
    if (surface.isLight()) StarColorOnLight else StarColor

/** [starColorOn] for the surface of the current theme (not the system's: the app can differ). */
@Composable
internal fun starColor(): Color = starColorOn(MaterialTheme.colorScheme.surface)
