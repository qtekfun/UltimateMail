// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.ConversationSummary
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What the list header and the empty states need to know about a scope. */
data class InboxStatus(
    /** The folder shown; null for the unified inbox. */
    val folderName: String?,
    val folderRole: FolderRole?,
    /** False until the first sync has stored something for the folder(s) (RF-10). */
    val synced: Boolean
)

/** Conversations of a folder or of the unified inbox, straight from Room and always reactive. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxListing @Inject constructor(database: UltimateMailDatabase) {
    private val conversations = database.conversationDao()
    private val folders = database.folderDao()
    private val accounts = database.accountDao()

    /** The newest [limit] conversations of [scope]; re-emits whenever Room changes. */
    fun observe(scope: InboxScope, limit: Int): Flow<List<ConversationItem>> = when (scope) {
        is InboxScope.Folder ->
            conversations.observeConversations(scope.accountId, scope.path, limit)

        InboxScope.Unified -> conversations.observeUnifiedInbox(limit)
    }.map { list -> list.map { it.toItem() } }

    /** Folder name and whether it has ever been synced, for titles and empty states. */
    fun observeStatus(scope: InboxScope): Flow<InboxStatus> = when (scope) {
        is InboxScope.Folder -> folders.observeAll(scope.accountId).map { all ->
            val folder = all.firstOrNull { it.path == scope.path }
            InboxStatus(folder?.name, folder?.role, folder?.uidValidity != null)
        }

        InboxScope.Unified -> accounts.observeAll().flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { folders.observeAll(it.id) }) { perAccount ->
                    perAccount.flatMap { it }
                }
            }
        }.map { all ->
            val inboxes = all.filter { it.role == FolderRole.INBOX }
            InboxStatus(null, null, inboxes.any { it.uidValidity != null })
        }
    }

    private fun ConversationSummary.toItem() = ConversationItem(
        accountId = latest.accountId,
        folderPath = latest.folderPath,
        threadId = latest.threadId,
        latestMessageId = latest.id,
        senderName = latest.senderName,
        senderAddress = latest.senderAddress,
        subject = latest.subject,
        snippet = latest.snippet,
        sentAt = latest.sentAt,
        messageCount = messageCount,
        unreadCount = unreadCount,
        flagged = latest.flagged,
        hasAttachments = latest.hasAttachments,
        labels = latest.labels,
        pendingSync = latest.pendingSync
    )
}
