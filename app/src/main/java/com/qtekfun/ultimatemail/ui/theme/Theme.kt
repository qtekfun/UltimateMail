// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(primary = Blue40, secondary = Teal40, tertiary = Amber40)

private val DarkColors = darkColorScheme(primary = Blue80, secondary = Teal80, tertiary = Amber80)

/** The color scheme: the wallpaper colors where they exist (Android 12+), else the palette. */
fun colorSchemeFor(dark: Boolean, dynamicLight: ColorScheme?, dynamicDark: ColorScheme?): ColorScheme =
    (if (dark) dynamicDark else dynamicLight) ?: if (dark) DarkColors else LightColors

@Composable
fun UltimateMailTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = colorSchemeFor(
        dark = isSystemInDarkTheme(),
        dynamicLight = if (dynamic) dynamicLightColorScheme(context) else null,
        dynamicDark = if (dynamic) dynamicDarkColorScheme(context) else null
    )
    MaterialTheme(colorScheme = colorScheme, typography = UltimateMailTypography, content = content)
}
