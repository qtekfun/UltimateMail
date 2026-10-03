// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** The folders of an account as the folder menu shows them, straight from Room (RF-02). */
class FolderListing @Inject constructor(database: UltimateMailDatabase) {
    private val folders = database.folderDao()

    /** Whatever was last synced, ordered for display, with unread counts. Works offline. */
    fun observe(accountId: Long): Flow<FolderTree> = combine(
        folders.observeAll(accountId),
        folders.observeUnread(accountId)
    ) { all, unread ->
        FolderTree.build(all, unread.associate { it.path to it.unread })
    }

    /** The Inbox of [accountId]; before its first sync the folder is not known, so "INBOX". */
    suspend fun inboxOf(accountId: Long): InboxScope.Folder {
        val inbox = observe(accountId).first().inboxPath
        return InboxScope.Folder(accountId, inbox ?: DEFAULT_INBOX)
    }

    companion object {
        const val DEFAULT_INBOX = "INBOX"
    }
}
