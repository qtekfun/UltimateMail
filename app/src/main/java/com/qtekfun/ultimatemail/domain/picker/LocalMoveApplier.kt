// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import javax.inject.Inject

/**
 * Makes the local state show a move or a label change at once, while the operation waits in the
 * queue (RF-06, RF-10). Call it after the operations were queued, with the same operations, and
 * also with the inverse ones to undo.
 *
 * What it does, and why it is not "move the row":
 * - The queue and the sync engine name a message by the (folder, UID) the user saw it under, and
 *   [com.qtekfun.ultimatemail.sync.engine.MailOperationExecutor] reads that row for the Message-ID
 *   and X-GM-MSGID it checks the server against ([com.qtekfun.ultimatemail.sync.conflict.TargetResolver]).
 *   Moving or deleting the row would lose that identity, and the next pull of the old folder would
 *   insert the message again until the server has really moved it.
 * - So a **move** leaves the row alone: the message list hides a message with a queued move (see
 *   `ConversationDao`), which also makes the undo free (the queue cancels the move and the message
 *   is visible again), and the executor deletes the old row once the server confirms. Here the
 *   move only refreshes the pending indicator.
 * - A **label change** is written to the row's labels (the chips of the list), and the same
 *   goes for undo. A pull that happens before the server applied it may show the old labels for a
 *   moment: pushes go before pulls, so that window is small.
 */
class LocalMoveApplier @Inject constructor(
    private val messages: MessageDao,
    private val operations: PendingOperationDao,
    private val marker: PendingSyncMarker
) {
    suspend fun apply(changes: List<NewOperation>) {
        changes.forEach { change ->
            when (change.type) {
                OperationType.ADD_LABEL -> editLabels(change) { it + change.payload }

                OperationType.REMOVE_LABEL -> editLabels(change) { labels ->
                    labels - change.payload
                }

                else -> Unit
            }
        }
        changes.map { Triple(it.accountId, it.folderPath, it.uid) }.distinct()
            .forEach { (accountId, folder, uid) -> refreshPending(accountId, folder, uid) }
    }

    private suspend fun editLabels(change: NewOperation, edit: (Set<String>) -> Set<String>) {
        val row = messages.get(change.accountId, change.folderPath, change.uid) ?: return
        // The order the server gave is kept; a new label goes last.
        val labels = edit(row.labels.toSet()).toList()
        messages.setLabels(change.accountId, change.folderPath, change.uid, labels)
    }

    /** The indicator follows the queue: set while an operation waits, clear once none does. */
    private suspend fun refreshPending(accountId: Long, folder: String, uid: Long) {
        if (operations.countForMessage(accountId, folder, uid) > 0) {
            marker.mark(accountId, folder, uid)
        } else {
            messages.setPendingSync(accountId, folder, uid, pending = false)
        }
    }
}
