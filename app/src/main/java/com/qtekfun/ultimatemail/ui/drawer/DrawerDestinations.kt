// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.ui.nav.Screen

/** A link at the bottom of the side menu to a screen outside the conversation list. */
data class DrawerDestination(
    val screen: Screen,
    @StringRes val label: Int,
    val icon: ImageVector,
    /** False while the screen does not exist yet: the entry is then not shown at all. */
    val available: Boolean
)

/**
 * The extension point of the side menu footer. A later task that adds a screen (settings T21,
 * for example) sets `available = true` on its entry here and handles its [Screen] in `AppRoot`.
 */
object DrawerDestinations {
    private val all = listOf(
        // T21: flip to true when the settings screen exists.
        DrawerDestination(Screen.Settings, R.string.drawer_settings, Icons.Filled.Settings, false)
    )

    val footer: List<DrawerDestination> = all.filter { it.available }
}
