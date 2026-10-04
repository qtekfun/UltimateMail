// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.InboxScope

/** The Inbox of one account as a row at the top of the menu. */
data class AccountInbox(val account: AccountSummary, val scope: InboxScope.Folder, val unread: Int)

/**
 * One account's collapsible section: its folders and labels. [folders] is empty while the
 * section is closed, so a closed account costs one row however many labels it has.
 */
data class AccountSection(
    val account: AccountSummary,
    val open: Boolean,
    val folders: List<FolderListItem>,
    /** The nested parents that are open inside the section. */
    val expanded: Set<String>
)

/**
 * The mailboxes screen (the side menu) as data: the inbox of every account, the special
 * mailboxes of the selected account and one section per account.
 *
 * The unified scope has no folders other than the Inboxes, so Starred, Drafts, Sent, Archive,
 * Spam and Trash are those of one account: the selected one, which follows the last account the
 * user opened and is the first one at the start.
 */
data class MailboxMenu(
    val inboxes: List<AccountInbox> = emptyList(),
    val special: List<FolderListItem> = emptyList(),
    val sections: List<AccountSection> = emptyList()
) {
    /** Unread in the Inboxes of every account: what "All inboxes" counts. */
    val unifiedUnread: Int get() = inboxes.sumOf { it.unread }

    companion object {
        /** The special mailboxes under the inboxes, in the order they are listed. */
        private val specialOrder = listOf(
            FolderRole.STARRED,
            FolderRole.DRAFTS,
            FolderRole.SENT,
            FolderRole.ARCHIVE,
            FolderRole.ALL_MAIL,
            FolderRole.JUNK,
            FolderRole.TRASH
        )

        fun build(
            accounts: List<AccountSummary>,
            trees: Map<Long, FolderTree>,
            selected: AccountSummary?,
            expansion: FolderExpansion
        ): MailboxMenu {
            val inboxes = accounts.map { account ->
                val tree = trees[account.id] ?: FolderTree.Empty
                AccountInbox(
                    account,
                    InboxScope.Folder(account.id, tree.inboxPath ?: FolderListing.DEFAULT_INBOX),
                    tree.special.firstOrNull { it.role == FolderRole.INBOX }?.unread ?: 0
                )
            }
            val special = selected?.let { trees[it.id] }?.special.orEmpty()
                .filter { it.role in specialOrder }
                .sortedBy { specialOrder.indexOf(it.role) }
            val sections = accounts.map { account ->
                val open = expansion.isSectionOpen(account.id)
                val expanded = expansion.pathsOf(account.id)
                val tree = trees[account.id] ?: FolderTree.Empty
                AccountSection(
                    account,
                    open,
                    if (open) tree.visible(expanded) else emptyList(),
                    expanded
                )
            }
            return MailboxMenu(inboxes, special, sections)
        }
    }
}
