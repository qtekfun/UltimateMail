// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The folders of an account as the folder list shows them, straight from Room (RF-02). */
class FolderListing @Inject constructor(database: UltimateMailDatabase) {
    private val folders = database.folderDao()

    /** Whatever was last synced, ordered for display, with unread counts. Works offline. */
    fun observe(accountId: Long): Flow<List<FolderListItem>> = combine(
        folders.observeAll(accountId),
        folders.observeUnread(accountId)
    ) { all, unread ->
        FolderOrdering.order(all, unread.associate { it.path to it.unread })
    }
}
