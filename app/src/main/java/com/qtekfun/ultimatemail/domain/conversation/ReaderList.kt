// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.ConversationKey
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * The list the reader was opened from: its [scope] and whether it showed only the unread
 * conversations. The arrows of the reading screen walk this list.
 */
data class ReaderList(val scope: InboxScope, val unreadOnly: Boolean = false) {
    /** Stable text form, to keep the list in saved state. */
    val key: String get() = (if (unreadOnly) UNREAD_MARK else ALL_MARK) + scope.key

    companion object {
        private const val ALL_MARK = "a:"
        private const val UNREAD_MARK = "u:"

        /** The list for a [key] made by [ReaderList.key]; null when it is not one. */
        fun fromKey(key: String?): ReaderList? {
            if (key == null || key.length <= ALL_MARK.length) return null
            val unread = when (key.take(ALL_MARK.length)) {
                ALL_MARK -> false
                UNREAD_MARK -> true
                else -> return null
            }
            return InboxScope.fromKey(key.substring(ALL_MARK.length))
                ?.let { ReaderList(it, unread) }
        }
    }
}

/** The conversations just before and after the open one in its list; null at the ends. */
data class Neighbours(val previous: ConversationRef? = null, val next: ConversationRef? = null)

/**
 * Finds the neighbours of an open conversation in the list it was opened from, straight from
 * Room, one row per side, so the screen never loads the list. "Previous" is the one above it
 * (newer), "next" the one below (older), as in the list. Both follow the list as it changes.
 */
class ReaderNeighbours @Inject constructor(database: UltimateMailDatabase) {
    private val conversations = database.conversationDao()

    fun observe(ref: ConversationRef, list: ReaderList?): Flow<Neighbours> {
        if (list == null) return flowOf(Neighbours())
        val unread = if (list.unreadOnly) 1 else 0
        val (newer, older) = when (val scope = list.scope) {
            is InboxScope.Folder -> conversations.observeNewerInFolder(
                scope.accountId, scope.path, ref.accountId, ref.folderPath, ref.threadId, unread
            ) to conversations.observeOlderInFolder(
                scope.accountId, scope.path, ref.accountId, ref.folderPath, ref.threadId, unread
            )

            InboxScope.Unified -> conversations.observeNewerInUnified(
                ref.accountId, ref.folderPath, ref.threadId, unread
            ) to conversations.observeOlderInUnified(
                ref.accountId, ref.folderPath, ref.threadId, unread
            )
        }
        return combine(newer, older) { above, below ->
            Neighbours(above?.toRef(), below?.toRef())
        }.distinctUntilChanged()
    }

    private fun ConversationKey.toRef() = ConversationRef(accountId, folderPath, threadId)
}
