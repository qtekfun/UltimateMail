// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.di.IoDispatcher
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The persisted queue of changes waiting for the server (RF-10, SPEC section 5.5).
 *
 * - Operations live in Room and run in queue order per account; one [drain] at a time per account.
 * - Repeated changes to a message are merged while the earlier one was not handed to the server.
 * - Operations on the same message never overtake each other: while one is waiting for a retry
 *   or failed, the later ones of that message wait too. Other messages carry on.
 * - Failures back off exponentially ([Backoff]); a permanent refusal parks the operation as failed
 *   until the user calls [retry] or [discard].
 * - An operation is marked as started before it is handed over, and stays queued until the server
 *   confirms. After a crash it is simply handed over again, which the [OperationExecutor] contract
 *   makes safe.
 *
 * Only reason codes are stored as errors, never message content.
 */
@Singleton
class OperationQueue @Inject constructor(
    private val dao: PendingOperationDao,
    private val executor: OperationExecutor,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) : HeldOperations {
    private val backoff = Backoff()
    private val merger = OperationMerger(dao, clock)
    private val accountLocks = ConcurrentHashMap<Long, Mutex>()

    /**
     * Queues [operation], merging it into a queued one of the same message when possible.
     * Returns the id of the queued operation that now carries the change, or null if it cancelled
     * out an earlier one (a move back to the original folder).
     */
    suspend fun enqueue(operation: NewOperation): Long? =
        withContext(io) { merger.enqueue(operation) }

    override suspend fun releaseHeld(accountId: Long) =
        withContext(io) { dao.releaseHeld(accountId, clock.instant()) }

    /**
     * Runs the operations of [accountId] that are due, in queue order, and says how they went.
     * Cancelling it stops before the next operation; one being executed stays queued as started
     * and is handed over again by the next drain.
     */
    suspend fun drain(accountId: Long): DrainSummary = withContext(io) {
        accountLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            var summary = DrainSummary()
            val waiting = mutableSetOf<Pair<String, Long>>()
            val now = clock.instant()
            for (queued in dao.all(accountId)) {
                currentCoroutineContext().ensureActive()
                val message = queued.folderPath to queued.uid
                val skipped = message in waiting || queued.failed || queued.nextAttemptAt > now
                val outcome = if (skipped) null else run(queued.id)
                summary += outcome
                if (skipped || outcome is OperationOutcome.RetryLater ||
                    outcome is OperationOutcome.Rejected
                ) {
                    waiting += message
                }
            }
            summary
        }
    }

    /** Hands one operation over and records the outcome; null if it vanished meanwhile. */
    private suspend fun run(id: Long): OperationOutcome? {
        dao.markStarted(id, clock.instant())
        // Read after marking: from here on no merge can change the payload any more.
        val operation = dao.get(id) ?: return null
        val outcome = try {
            executor.execute(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            // Whatever the executor throws is a failed attempt; its message may hold user data.
            OperationOutcome.RetryLater(EXECUTOR_ERROR)
        }
        when (outcome) {
            OperationOutcome.Done -> dao.delete(id)

            is OperationOutcome.RetryLater -> {
                val attempts = operation.attempts + 1
                val next = clock.instant().plus(backoff.delayAfter(attempts))
                dao.markRetryLater(id, attempts, next, outcome.reason.take(MAX_REASON_LENGTH))
            }

            is OperationOutcome.Rejected -> dao.markFailed(
                id,
                outcome.reason.take(MAX_REASON_LENGTH)
            )
        }
        return outcome
    }

    /** Gives a failed operation a fresh start; it runs in the next drain. */
    suspend fun retry(id: Long) = withContext(io) { dao.resetFailed(id, clock.instant()) }

    /** Drops an operation for good, whatever its state: the user gives up on that change. */
    suspend fun discard(id: Long) = withContext(io) { dao.delete(id) }

    /** Everything of the account not yet on the server, failed operations included. */
    fun observePendingCount(accountId: Long): Flow<Int> = dao.observeCount(accountId)

    /** The same for one message, for its "pending sync" indicator. */
    fun observePendingCount(accountId: Long, folderPath: String, uid: Long): Flow<Int> =
        dao.observeMessageCount(accountId, folderPath, uid)

    /** Operations the server refused, waiting for the user. */
    fun observeFailedCount(accountId: Long): Flow<Int> = dao.observeFailedCount(accountId)

    private companion object {
        const val EXECUTOR_ERROR = "executor_error"
        const val MAX_REASON_LENGTH = 64
    }
}
