// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.data.settings.DisplayDensity

/**
 * The sizes that change with the display density setting. The two bigger levels keep touch
 * targets at 48dp or more; [DisplayDensity.COMPACT] goes below on purpose, like Gmail's compact
 * view, because the user asked for more on screen.
 */
data class DensityMetrics(
    /** Height of one entry of the side menu. */
    val drawerRowHeight: Dp,
    /** Vertical padding above and below the content of a conversation row. */
    val listRowVerticalPadding: Dp,
    /** Minimum height of a conversation row. */
    val listRowMinHeight: Dp
)

/** The metrics of each density level, from roomiest to tightest. */
fun DisplayDensity.metrics(): DensityMetrics = when (this) {
    DisplayDensity.COMFORTABLE -> DensityMetrics(56.dp, 12.dp, 80.dp)
    DisplayDensity.DEFAULT -> DensityMetrics(48.dp, 8.dp, 68.dp)
    DisplayDensity.COMPACT -> DensityMetrics(40.dp, 4.dp, 56.dp)
}

/** The metrics of the current density, provided by [UltimateMailTheme]. */
val LocalDensityMetrics = compositionLocalOf { DisplayDensity.DEFAULT.metrics() }
