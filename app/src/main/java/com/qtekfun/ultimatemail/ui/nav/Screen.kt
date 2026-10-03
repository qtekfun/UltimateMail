// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import com.qtekfun.ultimatemail.domain.conversation.ConversationRef
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.search.SearchScope
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The screens of the app. Navigation is a plain state (see [AppNavigator]) instead of a
 * navigation library: there are only a few screens and no new dependency is worth that.
 *
 * Adding a destination (search T20, settings T21): add an object or
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

    /**
     * Reading one conversation (T15): the thread [threadId] of [folderPath] of the account
     * [accountId], full screen. Back returns to the screen it was opened from.
     */
    data class Conversation(val accountId: Long, val folderPath: String, val threadId: String) :
        Screen {
        override val route = CONVERSATION_PREFIX + accountId + ":" + encode(folderPath) + ":" +
            encode(threadId)

        val ref: ConversationRef get() = ConversationRef(accountId, folderPath, threadId)
    }

    /** Settings (T21), reached from the side menu. */
    data object Settings : Screen {
        override val route = "settings"
    }

    /** The settings of one account (T21, T19), reached from [Settings]; Back returns there. */
    data class AccountSettings(val accountId: Long) : Screen {
        override val route = ACCOUNT_SETTINGS_PREFIX + accountId
    }

    /**
     * Signing in again to the account [accountId] after its credentials stopped working (T27):
     * a password, or the provider's browser sign-in. Back returns to where it was opened from.
     */
    data class Reauth(val accountId: Long) : Screen {
        override val route = REAUTH_PREFIX + accountId
    }

    /** Search (T20), opened from the top bar of the conversation list in [initialScope]. */
    data class Search(val initialScope: SearchScope) : Screen {
        override val route = SEARCH_PREFIX + initialScope.key
    }

    companion object {
        private const val REAUTH_PREFIX = "reauth:"
        private const val SEARCH_PREFIX = "search:"
        private const val INBOX_PREFIX = "inbox:"
        private const val CONVERSATION_PREFIX = "conversation:"
        private const val CONVERSATION_PARTS = 3
        private const val ACCOUNT_SETTINGS_PREFIX = "account-settings:"

        /** The screen for a saved [route]; anything unknown goes back to the start screen. */
        fun fromRoute(route: String?): Screen =
            if (route != null && route.startsWith(SEARCH_PREFIX)) {
                Search(
                    SearchScope.fromKey(route.removePrefix(SEARCH_PREFIX))
                        ?: SearchScope.AllAccounts
                )
            } else {
                otherFromRoute(route)
            }

        private fun otherFromRoute(route: String?): Screen = when {
            route == AddAccount.route -> AddAccount

            route == Settings.route -> Settings

            route != null && route.startsWith(CONVERSATION_PREFIX) ->
                conversationFromRoute(route.removePrefix(CONVERSATION_PREFIX)) ?: Home

            route != null && route.startsWith(ACCOUNT_SETTINGS_PREFIX) ->
                route.removePrefix(ACCOUNT_SETTINGS_PREFIX).toLongOrNull()
                    ?.let(::AccountSettings) ?: Home

            route != null && route.startsWith(INBOX_PREFIX) ->
                InboxScope.fromKey(route.removePrefix(INBOX_PREFIX))?.let(::Inbox) ?: Home

            else -> reauthFromRoute(route)
        }

        /** The sign-in-again screen for a saved [route]; anything else is the start screen. */
        private fun reauthFromRoute(route: String?): Screen =
            route?.removePrefix(REAUTH_PREFIX)?.takeIf { route.startsWith(REAUTH_PREFIX) }
                ?.toLongOrNull()?.let(::Reauth) ?: Home

        private fun conversationFromRoute(text: String): Conversation? {
            val parts = text.split(':')
            if (parts.size != CONVERSATION_PARTS) return null
            val accountId = parts[0].toLongOrNull()
            val path = decode(parts[1])
            val thread = decode(parts[2])
            return if (accountId != null && path.isNotEmpty() && thread.isNotEmpty()) {
                Conversation(accountId, path, thread)
            } else {
                null
            }
        }

        /** Folder paths and thread ids may hold any character, the route separator included. */
        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

        private fun decode(text: String) = URLDecoder.decode(text, "UTF-8")
    }
}
