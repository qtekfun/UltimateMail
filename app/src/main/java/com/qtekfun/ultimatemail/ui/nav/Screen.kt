// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import com.qtekfun.ultimatemail.domain.inbox.InboxScope

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

    /** The conversations of one folder, or of the unified inbox ([InboxScope.Unified]). */
    data class Inbox(val scope: InboxScope) : Screen {
        override val route = INBOX_PREFIX + scope.key
    }

    companion object {
        private const val INBOX_PREFIX = "inbox:"

        /** The screen for a saved [route]; anything unknown goes back to the start screen. */
        fun fromRoute(route: String?): Screen = when {
            route == AddAccount.route -> AddAccount

            route != null && route.startsWith(INBOX_PREFIX) ->
                InboxScope.fromKey(route.removePrefix(INBOX_PREFIX))?.let(::Inbox) ?: Folders

            else -> Folders
        }
    }
}
