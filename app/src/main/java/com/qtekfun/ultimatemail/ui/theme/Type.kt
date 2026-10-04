// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private fun style(size: TextUnit, line: TextUnit, weight: FontWeight, tracking: TextUnit = 0.em) =
    TextStyle(
        fontWeight = weight,
        fontSize = size,
        lineHeight = line,
        letterSpacing = tracking
    )

/**
 * A finer scale than Material's default, in the spirit of iOS Mail: about a tenth smaller, with
 * tighter line heights and no letter spacing except on the smallest labels. Everything still
 * follows the system font scale, which stays the way to get bigger text (200% is supported).
 */
internal val UltimateMailTypography = Typography(
    displayLarge = style(48.sp, 56.sp, FontWeight.Normal),
    displayMedium = style(40.sp, 48.sp, FontWeight.Normal),
    displaySmall = style(32.sp, 40.sp, FontWeight.Normal),
    headlineLarge = style(28.sp, 34.sp, FontWeight.SemiBold),
    headlineMedium = style(26.sp, 32.sp, FontWeight.SemiBold),
    headlineSmall = style(20.sp, 26.sp, FontWeight.SemiBold),
    titleLarge = style(19.sp, 25.sp, FontWeight.SemiBold),
    titleMedium = style(15.sp, 20.sp, FontWeight.Medium),
    titleSmall = style(13.sp, 18.sp, FontWeight.Medium),
    bodyLarge = style(15.sp, 21.sp, FontWeight.Normal),
    bodyMedium = style(13.sp, 18.sp, FontWeight.Normal),
    bodySmall = style(11.sp, 15.sp, FontWeight.Normal, 0.1.em),
    labelLarge = style(13.sp, 18.sp, FontWeight.Medium),
    labelMedium = style(11.sp, 14.sp, FontWeight.Medium, 0.1.em),
    labelSmall = style(10.sp, 13.sp, FontWeight.Medium, 0.1.em)
)
