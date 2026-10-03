// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.InboxScope

/** Which conversations the main screen shows when the user has not picked anything yet. */
object ShellScope {
    /**
     * The start scope: the unified inbox with several accounts, the Inbox of the only account
     * otherwise, and null without accounts. [inboxPath] is that account's Inbox, if synced.
     */
    fun default(accounts: List<AccountSummary>, inboxPath: String?): InboxScope? = when {
        accounts.isEmpty() -> null
        accounts.size > 1 -> InboxScope.Unified
        else -> InboxScope.Folder(accounts.single().id, inboxPath ?: FolderListing.DEFAULT_INBOX)
    }

    /**
     * What to show for a [requested] scope: itself, or [default] when nothing was requested or
     * its account has been removed meanwhile.
     */
    fun resolve(
        requested: InboxScope?,
        accounts: List<AccountSummary>,
        default: InboxScope?
    ): InboxScope? = when {
        requested is InboxScope.Folder && accounts.none { it.id == requested.accountId } -> default
        requested == null -> default
        else -> requested
    }
}
