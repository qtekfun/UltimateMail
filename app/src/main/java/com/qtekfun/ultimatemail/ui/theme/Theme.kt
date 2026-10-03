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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode

private val LightColors = lightColorScheme(primary = Blue40, secondary = Teal40, tertiary = Amber40)

private val DarkColors = darkColorScheme(primary = Blue80, secondary = Teal80, tertiary = Amber80)

/**
 * The color scheme for [settings]: the theme mode decides light or dark ([systemDark] is what the
 * system uses); the wallpaper colors ([dynamicLight], [dynamicDark], null where the device has
 * none) are used when dynamic color is on, else the palette; AMOLED only affects the dark theme.
 */
fun colorSchemeFor(
    settings: AppSettings,
    systemDark: Boolean,
    dynamicLight: ColorScheme?,
    dynamicDark: ColorScheme?
): ColorScheme {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamic = if (settings.dynamicColor) (if (dark) dynamicDark else dynamicLight) else null
    val scheme = dynamic ?: if (dark) DarkColors else LightColors
    return if (dark && settings.amoled) scheme.toAmoled() else scheme
}

/** Pure black behind everything, for OLED screens; containers stay just visible. */
internal fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = AmoledLow,
    surfaceContainer = AmoledContainer,
    surfaceContainerHigh = AmoledHigh,
    surfaceContainerHighest = AmoledHighest,
    surfaceBright = AmoledHighest
)

@Composable
fun UltimateMailTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = colorSchemeFor(
        settings = settings,
        systemDark = isSystemInDarkTheme(),
        dynamicLight = if (dynamic) dynamicLightColorScheme(context) else null,
        dynamicDark = if (dynamic) dynamicDarkColorScheme(context) else null
    )
    CompositionLocalProvider(LocalDensityMetrics provides settings.density.metrics()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UltimateMailTypography,
            content = content
        )
    }
}
