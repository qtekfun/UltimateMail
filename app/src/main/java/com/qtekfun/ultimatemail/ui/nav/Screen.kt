// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import com.qtekfun.ultimatemail.domain.inbox.InboxScope

/**
 * The screens of the app. Navigation is a plain state (see [AppNavigator]) instead of a
 * navigation library: there are only a few screens and no new dependency is worth that.
 *
 * Adding a destination (reading a conversation T15, search T20, settings T21): add an object or
 * data class here with a stable [route], teach [fromRoute] about it, handle it in the `when` of
 * `AppRoot`, and, if the side menu should link to it, add an entry to `DrawerDestinations`.
 */
sealed interface Screen {
    /** Stable name used to restore the screen after the process is recreated. */
    val route: String

    /**
     * Start screen: the main shell (side menu and conversation list) showing the default
     * scope, i.e. the unified inbox with several accounts and the Inbox of the only one.
     */
    data object Home : Screen {
        override val route = "home"
    }

    data object AddAccount : Screen {
        override val route = "add-account"
    }

    /** The main shell showing the conversations of one folder or of [InboxScope.Unified]. */
    data class Inbox(val scope: InboxScope) : Screen {
        override val route = INBOX_PREFIX + scope.key
    }

    /** Settings (T21). Reachable from the side menu once that screen exists. */
    data object Settings : Screen {
        override val route = "settings"
    }

    companion object {
        private const val INBOX_PREFIX = "inbox:"

        /** The screen for a saved [route]; anything unknown goes back to the start screen. */
        fun fromRoute(route: String?): Screen = when {
            route == AddAccount.route -> AddAccount

            route == Settings.route -> Settings

            route != null && route.startsWith(INBOX_PREFIX) ->
                InboxScope.fromKey(route.removePrefix(INBOX_PREFIX))?.let(::Inbox) ?: Home

            else -> Home
        }
    }
}
