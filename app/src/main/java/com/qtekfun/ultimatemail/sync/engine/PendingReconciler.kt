// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import javax.inject.Inject

/**
 * What a pull does about the operations still waiting in the queue (SPEC section 5):
 *
 * - After a UIDVALIDITY reset, operations that waited on a message by its old UID are moved to
 *   the message's new UID, found by Message-ID (rules 2 and 4). Those whose message is gone are
 *   parked as failed with the reason `message_gone`, for the user to see.
 * - Pending flag changes are applied again over what the server just said (rule 1).
 * - The "pending sync" indicator of every message of the folder is set from the queue.
 */
internal class PendingReconciler @Inject constructor(
    private val messages: MessageDao,
    private val operations: PendingOperationDao
) {
    /** Called first on a UIDVALIDITY change, before the folder's messages are deleted. Idempotent. */
    suspend fun detach(accountId: Long, folderPath: String) {
        operations.failOrphansOf(accountId, folderPath)
        operations.detachFromServerUids(accountId, folderPath)
        messages.parkReferencedByOperations(accountId, folderPath)
    }

    /** Called when a folder has been pulled completely. */
    suspend fun settle(accountId: Long, folderPath: String) {
        rebase(accountId, folderPath)
        val queued = operations.all(accountId).filter { it.folderPath == folderPath }
        overlayFlags(accountId, folderPath, queued)
        val waiting = queued.map { it.uid }.toSet()
        messages.pendingUids(accountId, folderPath)
            .filter { it !in waiting }
            .forEach { messages.setPendingSync(accountId, folderPath, it, pending = false) }
        waiting.forEach { messages.setPendingSync(accountId, folderPath, it, pending = true) }
    }

    private suspend fun rebase(accountId: Long, folderPath: String) {
        operations.all(accountId)
            .filter { it.folderPath == folderPath && it.uid <= 0 && !it.failed && it.isServerOperation() }
            .forEach { operation ->
                val placeholder = messages.get(accountId, folderPath, operation.uid)
                val newUid = placeholder?.messageId
                    ?.let { messages.uidOfMessageId(accountId, folderPath, it) }
                if (newUid != null) {
                    operations.rebase(operation.id, newUid)
                } else {
                    operations.markFailed(operation.id, MESSAGE_GONE)
                }
                // The placeholder only carried the message across the reset.
                if (operations.countForMessage(accountId, folderPath, operation.uid) == 0) {
                    messages.delete(accountId, folderPath, operation.uid)
                }
            }
    }

    private suspend fun overlayFlags(
        accountId: Long,
        folderPath: String,
        queued: List<PendingOperationEntity>
    ) {
        queued.filter { it.type == OperationType.SET_FLAGS && !it.failed }
            .groupBy { it.uid }
            .forEach { (uid, changes) ->
                val message = messages.get(accountId, folderPath, uid) ?: return@forEach
                val change = changes.sortedBy { it.id }
                    .mapNotNull { runCatching { FlagChange.decode(it.payload) }.getOrNull() }
                    .reduceOrNull { earlier, later -> later.over(earlier) } ?: return@forEach
                messages.setFlags(
                    id = message.id,
                    seen = change.seen ?: message.seen,
                    flagged = change.flagged ?: message.flagged,
                    answered = message.answered,
                    pendingSync = true
                )
            }
    }

    private fun PendingOperationEntity.isServerOperation() =
        type != OperationType.SEND && type != OperationType.SAVE_DRAFT

    internal companion object {
        const val MESSAGE_GONE = "message_gone"
    }
}
