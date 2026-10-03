// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Queues new operations, folding repeated changes to one message into the one still waiting.
 * Only operations never handed to the server are touched, and the database refuses the change
 * itself if the sender got there first, in which case the new operation is simply queued.
 *
 * Operations are expected to refer to a message by the (folder, uid) the user sees it under, so a
 * change made after a queued move still names the original folder.
 */
internal class OperationMerger(private val dao: PendingOperationDao, private val clock: Clock) {
    private val lock = Mutex()

    suspend fun enqueue(operation: NewOperation): Long? = lock.withLock {
        val unsent = dao.forMessage(operation.accountId, operation.folderPath, operation.uid)
            .filter { it.startedAt == null }
        when (operation.type) {
            OperationType.SET_FLAGS -> mergeFlags(operation, unsent.lastOfType(operation.type))

            OperationType.MOVE -> mergeMove(operation, unsent.lastOfType(operation.type))

            // The message goes away, so changing its flags first would be wasted work.
            OperationType.DELETE -> {
                unsent.filter { it.type == OperationType.SET_FLAGS }
                    .forEach { dao.deleteUnstarted(it.id) }
                insert(operation)
            }

            else -> insert(operation)
        }
    }

    private suspend fun mergeFlags(
        operation: NewOperation,
        earlier: PendingOperationEntity?
    ): Long {
        if (earlier == null) return insert(operation)
        val merged = FlagChange.decode(operation.payload).over(FlagChange.decode(earlier.payload))
        val replaced = dao.replacePayload(earlier.id, merged.encode()) > 0
        return if (replaced) earlier.id else insert(operation)
    }

    private suspend fun mergeMove(
        operation: NewOperation,
        earlier: PendingOperationEntity?
    ): Long? {
        if (earlier == null) return insert(operation)
        val backHome = operation.payload == operation.folderPath
        return if (backHome) cancelMove(operation, earlier) else retargetMove(operation, earlier)
    }

    /** Moving back to the folder it never left: nothing to send any more. */
    private suspend fun cancelMove(
        operation: NewOperation,
        earlier: PendingOperationEntity
    ): Long? = if (dao.deleteUnstarted(earlier.id) > 0) null else insert(operation)

    private suspend fun retargetMove(
        operation: NewOperation,
        earlier: PendingOperationEntity
    ): Long =
        if (dao.replacePayload(earlier.id, operation.payload) > 0) earlier.id else insert(operation)

    private suspend fun insert(operation: NewOperation): Long = dao.enqueue(
        PendingOperationEntity(
            accountId = operation.accountId,
            type = operation.type,
            folderPath = operation.folderPath,
            uid = operation.uid,
            payload = operation.payload,
            createdAt = clock.instant()
        )
    )

    private fun List<PendingOperationEntity>.lastOfType(type: OperationType) =
        lastOrNull { it.type == type }
}
