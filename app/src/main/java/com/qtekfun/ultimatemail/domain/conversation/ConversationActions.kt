// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import javax.inject.Inject

/**
 * What moving messages can be taken back with: one inverse operation per message. The queue
 * cancels a move that was never handed to the server when the move back is queued.
 */
data class MoveUndo(val inverse: List<NewOperation>)

/**
 * The changes the reader makes to messages (RF-05): every one is applied to Room at once, so the
 * screen follows immediately, and queued as an operation for the server (RF-10) instead of being
 * sent from here.
 *
 * Flag changes also mark the message "pending sync" and ask for a sync. Moves do neither: the
 * lists already hide a message with a move waiting, and the caller asks for the sync with [sync]
 * once the undo window is over, so that an undo normally still finds the move in the queue.
 */
class ConversationActions @Inject constructor(
    private val messages: MessageDao,
    private val queue: OperationQueue,
    private val marker: PendingSyncMarker,
    private val scheduler: SyncScheduler
) {
    /** Marks the message as read; does nothing if it already is. */
    suspend fun markRead(messageId: Long) = changeFlags(messageId, seen = true)

    suspend fun markUnread(messageId: Long) = changeFlags(messageId, seen = false)

    suspend fun setStarred(messageId: Long, starred: Boolean) =
        changeFlags(messageId, flagged = starred)

    private suspend fun changeFlags(
        messageId: Long,
        seen: Boolean? = null,
        flagged: Boolean? = null
    ) {
        val row = messages.getById(messageId) ?: return
        val change = FlagChange(
            seen = seen?.takeIf { it != row.seen },
            flagged = flagged?.takeIf { it != row.flagged }
        )
        if (change.seen == null && change.flagged == null) return
        val onServer = row.uid > 0
        messages.setFlags(
            row.id,
            seen = change.seen ?: row.seen,
            flagged = change.flagged ?: row.flagged,
            answered = row.answered,
            pendingSync = onServer || row.pendingSync
        )
        if (onServer) {
            queue.enqueue(
                NewOperation(
                    row.accountId,
                    OperationType.SET_FLAGS,
                    row.folderPath,
                    row.uid,
                    change.encode()
                )
            )
            marker.mark(row.accountId, row.folderPath, row.uid)
            scheduler.requestSync(row.accountId)
        }
    }

    /**
     * Queues a move of [messageIds] to the folder [target]. Messages that are not on the server
     * yet or are already in [target] are skipped. Returns how to undo it, or null when nothing
     * was queued.
     */
    suspend fun move(messageIds: List<Long>, target: String): MoveUndo? {
        val moved = messageIds.mapNotNull { messages.getById(it) }
            .filter { it.uid > 0 && it.folderPath != target }
        val inverse = moved.map { row ->
            queue.enqueue(
                NewOperation(row.accountId, OperationType.MOVE, row.folderPath, row.uid, target)
            )
            // Back to where it was: the folder it is in now.
            NewOperation(row.accountId, OperationType.MOVE, row.folderPath, row.uid, row.folderPath)
        }
        return MoveUndo(inverse).takeIf { inverse.isNotEmpty() }
    }

    /** Takes a [move] back. */
    suspend fun undo(undo: MoveUndo) {
        undo.inverse.forEach { queue.enqueue(it) }
    }

    /** Asks for a sync of [accountId], which sends what is queued. */
    fun sync(accountId: Long) = scheduler.requestSync(accountId)
}
