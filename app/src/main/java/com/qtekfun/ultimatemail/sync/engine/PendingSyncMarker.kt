// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import javax.inject.Inject

/**
 * Keeps the per-message "pending sync" indicator (RF-10) in step with the operation queue. Code
 * that queues an operation calls [mark]; the executor calls [clearIfIdle] once the server has it,
 * and every pull refreshes the flag from the queue itself, so it cannot stay wrong for long.
 */
class PendingSyncMarker @Inject constructor(
    private val messages: MessageDao,
    private val operations: PendingOperationDao
) {
    suspend fun mark(accountId: Long, folderPath: String, uid: Long) =
        messages.setPendingSync(accountId, folderPath, uid, pending = true)

    /** Clears the flag of the message of [done], unless another operation still waits for it. */
    suspend fun clearIfIdle(done: PendingOperationEntity) {
        // [done] itself is still in the queue until the queue records its outcome.
        val others = operations.countForMessage(done.accountId, done.folderPath, done.uid) - 1
        if (others <= 0) {
            messages.setPendingSync(done.accountId, done.folderPath, done.uid, pending = false)
        }
    }
}
