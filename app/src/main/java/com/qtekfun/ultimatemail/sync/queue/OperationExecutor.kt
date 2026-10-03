// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity

/**
 * Sends one queued operation to the server. The real IMAP/SMTP implementation arrives with the
 * sync engine (T10).
 *
 * Contract: executing the same operation twice must have the same final effect as executing it
 * once. The queue hands an operation over again after a crash or timeout without knowing whether
 * the first attempt arrived (flags are set to absolute values, a move to a folder the message is
 * already in succeeds, deleting a vanished message succeeds). An executor that cannot guarantee
 * this for an operation (SEND) must check the server first and answer [OperationOutcome.Done].
 */
fun interface OperationExecutor {
    suspend fun execute(operation: PendingOperationEntity): OperationOutcome
}

/** What happened to an operation handed to an [OperationExecutor]. */
sealed interface OperationOutcome {
    /** The server has the change; the operation leaves the queue. */
    data object Done : OperationOutcome

    /** Network trouble or a transient server answer: try again later with backoff. */
    data class RetryLater(val reason: String) : OperationOutcome

    /** The server refused for good: no automatic retry; the user retries or discards. */
    data class Rejected(val reason: String) : OperationOutcome
}
