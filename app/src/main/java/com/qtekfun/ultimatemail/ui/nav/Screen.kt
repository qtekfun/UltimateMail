// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

/**
 * The screens of the app. Navigation is a plain state (see [AppNavigator]) instead of a
 * navigation library: there are only a few screens and no new dependency is worth that.
 */
sealed interface Screen {
    /** Stable name used to restore the screen after the process is recreated. */
    val route: String

    /** Start screen: the accounts and the folders of the selected one. */
    data object Folders : Screen {
        override val route = "folders"
    }

    data object AddAccount : Screen {
        override val route = "add-account"
    }

    companion object {
        /** The screen for a saved [route]; anything unknown goes back to the start screen. */
        fun fromRoute(route: String?): Screen = when (route) {
            AddAccount.route -> AddAccount
            else -> Folders
        }
    }
}
