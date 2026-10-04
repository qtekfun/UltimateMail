// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
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

/** What a flag change took away: the values of the flags it changed, null for the others. */
data class PriorFlags(val messageId: Long, val seen: Boolean?, val flagged: Boolean?)

/** What flag changes of several messages can be taken back with. */
data class FlagUndo(val prior: List<PriorFlags>)

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

    /**
     * Sets the flags of several messages at once (the list's swipe and selection, RF-05) and
     * returns how to undo it, or null when nothing changed. The sync is left to the caller
     * ([sync]), once the undo window is over, so that an undo finds the change in the queue.
     */
    suspend fun setFlags(
        messageIds: List<Long>,
        seen: Boolean? = null,
        flagged: Boolean? = null
    ): FlagUndo? {
        val prior = messageIds.mapNotNull { changeFlags(it, seen, flagged, sync = false) }
        return FlagUndo(prior).takeIf { prior.isNotEmpty() }
    }

    /** Takes a [setFlags] back; the queue merges the inverse into what it has not sent yet. */
    suspend fun undo(undo: FlagUndo) {
        undo.prior.forEach { changeFlags(it.messageId, it.seen, it.flagged, sync = false) }
    }

    private suspend fun changeFlags(
        messageId: Long,
        seen: Boolean? = null,
        flagged: Boolean? = null,
        sync: Boolean = true
    ): PriorFlags? {
        val row = messages.getById(messageId) ?: return null
        val change = FlagChange(
            seen = seen?.takeIf { it != row.seen },
            flagged = flagged?.takeIf { it != row.flagged }
        )
        return if (change.seen == null && change.flagged == null) {
            null
        } else {
            apply(row, change, sync)
        }
    }

    private suspend fun apply(row: MessageEntity, change: FlagChange, sync: Boolean): PriorFlags {
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
            if (sync) scheduler.requestSync(row.accountId)
        }
        return PriorFlags(
            row.id,
            seen = row.seen.takeIf { change.seen != null },
            flagged = row.flagged.takeIf { change.flagged != null }
        )
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
                NewOperation(
                    row.accountId,
                    OperationType.MOVE,
                    row.folderPath,
                    row.uid,
                    target,
                    holdFor = NewOperation.UNDO_HOLD
                )
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
