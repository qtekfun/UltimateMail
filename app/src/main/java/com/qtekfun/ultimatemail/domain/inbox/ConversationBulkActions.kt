// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.conversation.FlagUndo
import com.qtekfun.ultimatemail.domain.conversation.MoveUndo
import javax.inject.Inject

/** What a batch of changes can be taken back with, and whose accounts to sync once it stands. */
data class BatchUndo(
    val moves: List<MoveUndo>,
    val flags: List<FlagUndo>,
    val accountIds: Set<Long>
)

/** How a batch went: [applied] conversations changed (0 when none could be) and the [undo]. */
data class BulkResult(val change: RowChange, val applied: Int, val undo: BatchUndo?)

/** A message of a conversation, as far as moving it needs to know. */
data class MessageHandle(val id: Long, val accountId: Long, val folderPath: String, val uid: Long)

/**
 * What the conversation list does to whole conversations (all their messages in that folder):
 * the swipe and the selection bar (RF-03, RF-05). Every change is applied to Room at once and
 * queued per message through [ConversationActions] and the operation queue (RF-10); the caller
 * asks for the sync once the undo window is over.
 */
class ConversationBulkActions @Inject constructor(
    private val messages: MessageDao,
    private val actions: ConversationActions
) {
    /** Applies [change] to [items]; the ones it does not apply to (see [targets]) are skipped. */
    suspend fun apply(
        change: RowChange,
        items: List<ConversationItem>,
        targets: RowTargets
    ): BulkResult = when (change) {
        RowChange.ARCHIVE, RowChange.DELETE -> moveAway(change, items, targets)

        RowChange.MARK_READ -> setFlags(change, items, FlagTarget.READ) { item ->
            // Whatever is unread in the conversation, not just the newest message.
            thread(item).filter { !it.seen }.map { it.id }
        }

        // The same as the reading screen: only the newest message goes back to unread.
        RowChange.MARK_UNREAD ->
            setFlags(change, items, FlagTarget.UNREAD) { listOf(it.latestMessageId) }

        RowChange.STAR ->
            setFlags(change, items, FlagTarget.STARRED) { listOf(it.latestMessageId) }

        RowChange.UNSTAR ->
            setFlags(change, items, FlagTarget.UNSTARRED) { listOf(it.latestMessageId) }
    }

    private enum class FlagTarget { READ, UNREAD, STARRED, UNSTARRED }

    private suspend fun thread(item: ConversationItem) =
        messages.thread(item.accountId, item.folderPath, item.threadId)

    private suspend fun moveAway(
        change: RowChange,
        items: List<ConversationItem>,
        targets: RowTargets
    ): BulkResult {
        // One move per destination folder; each conversation goes where its own account says.
        val byDestination = items.mapNotNull { item ->
            val folders = targets.of(item) ?: return@mapNotNull null
            val path = when {
                change == RowChange.ARCHIVE && folders.canArchive -> folders.archivePath
                change == RowChange.DELETE && folders.canDelete -> folders.trashPath
                else -> null
            }
            path?.let { (item.accountId to it) to item }
        }.groupBy({ it.first }, { it.second })
        val undos = mutableListOf<MoveUndo>()
        var applied = 0
        val accounts = mutableSetOf<Long>()
        for ((destination, group) in byDestination) {
            val ids = group.flatMap { thread(it).map { row -> row.id } }
            val undo = actions.move(ids, destination.second) ?: continue
            undos += undo
            applied += group.size
            accounts += destination.first
        }
        return result(change, applied, BatchUndo(undos, emptyList(), accounts))
    }

    private suspend fun setFlags(
        change: RowChange,
        items: List<ConversationItem>,
        target: FlagTarget,
        messageIds: suspend (ConversationItem) -> List<Long>
    ): BulkResult {
        val undos = mutableListOf<FlagUndo>()
        val accounts = mutableSetOf<Long>()
        for (item in items) {
            val ids = messageIds(item)
            val undo = when (target) {
                FlagTarget.READ -> actions.setFlags(ids, seen = true)
                FlagTarget.UNREAD -> actions.setFlags(ids, seen = false)
                FlagTarget.STARRED -> actions.setFlags(ids, flagged = true)
                FlagTarget.UNSTARRED -> actions.setFlags(ids, flagged = false)
            } ?: continue
            undos += undo
            accounts += item.accountId
        }
        return result(change, undos.size, BatchUndo(emptyList(), undos, accounts))
    }

    private fun result(change: RowChange, applied: Int, undo: BatchUndo) =
        BulkResult(change, applied, undo.takeIf { applied > 0 })

    /** Takes a batch back and asks for the sync of what that left in the queue. */
    suspend fun undo(undo: BatchUndo) {
        undo.moves.forEach { actions.undo(it) }
        undo.flags.forEach { actions.undo(it) }
        undo.accountIds.forEach { actions.sync(it) }
    }

    /** The messages of [items], for the folder picker. */
    suspend fun messagesOf(items: List<ConversationItem>): List<MessageHandle> =
        items.flatMap { item ->
            thread(item).map { MessageHandle(it.id, it.accountId, it.folderPath, it.uid) }
        }
}
