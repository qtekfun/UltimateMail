// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.ThemeMode

private val LightColors = lightColorScheme(primary = Blue40, secondary = Teal40, tertiary = Amber40)

private val DarkColors = darkColorScheme(primary = Blue80, secondary = Teal80, tertiary = Amber80)

/**
 * Whether the app is dark: the theme mode decides ([systemDark] is what the system uses). The
 * system bars must follow this, not the system: a light system with the app in dark or AMOLED
 * black would otherwise draw dark clock and battery icons on a black screen.
 */
fun isDarkTheme(settings: AppSettings, systemDark: Boolean): Boolean = when (settings.theme) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

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
    val dark = isDarkTheme(settings, systemDark)
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
    val systemDark = isSystemInDarkTheme()
    SystemBarIcons(dark = isDarkTheme(settings, systemDark))
    val colorScheme = colorSchemeFor(
        settings = settings,
        systemDark = systemDark,
        dynamicLight = if (dynamic) dynamicLightColorScheme(context) else null,
        dynamicDark = if (dynamic) dynamicDarkColorScheme(context) else null
    )
    val appearance = rowAppearanceFor(
        settings = settings,
        dark = isDarkTheme(settings, systemDark),
        dynamicInUse = settings.dynamicColor && dynamic,
        primary = colorScheme.primary
    )
    CompositionLocalProvider(
        LocalDensityMetrics provides settings.density.metrics(),
        LocalRowAppearance provides appearance
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UltimateMailTypography,
            content = content
        )
    }
}

/** Light icons on the status and navigation bars when the app is [dark], dark ones otherwise. */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}
