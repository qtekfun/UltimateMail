// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.OperationType
import io.mockk.coEvery
import io.mockk.spyk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationQueueTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val t0 = Instant.ofEpochSecond(1_000_000)
    private val clock = MutableClock(t0)
    private val db = inMemoryDatabase()
    private val dao = db.pendingOperationDao()
    private var accountId = 0L

    private val executed = mutableListOf<PendingOperationEntity>()
    private var behaviour: suspend (PendingOperationEntity) -> OperationOutcome =
        { OperationOutcome.Done }
    private val executor = OperationExecutor {
        executed += it
        behaviour(it)
    }

    @BeforeEach
    fun setUp() = runTest { accountId = db.accountDao().insert(account()) }

    @AfterEach
    fun close() = db.close()

    private fun TestScope.queue(queueDao: PendingOperationDao = dao) =
        OperationQueue(queueDao, executor, clock, StandardTestDispatcher(testScheduler))

    private fun flags(seen: Boolean? = null, flagged: Boolean? = null, uid: Long = 1) =
        NewOperation(
            accountId,
            OperationType.SET_FLAGS,
            "INBOX",
            uid,
            FlagChange(seen, flagged).encode()
        )

    private fun move(to: String, uid: Long = 1) =
        NewOperation(accountId, OperationType.MOVE, "INBOX", uid, to)

    private fun op(type: OperationType, uid: Long = 1) =
        NewOperation(accountId, type, "INBOX", uid, "")

    private suspend fun stored() = dao.all(accountId)

    // --- enqueue, FIFO ---

    @Test
    fun `operations are persisted and executed in the order they were queued`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND, uid = 3))
        queue.enqueue(op(OperationType.SAVE_DRAFT, uid = 1))
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 2))

        assertEquals(listOf(3L, 1L, 2L), stored().map { it.uid })
        val summary = queue.drain(accountId)

        assertEquals(listOf(3L, 1L, 2L), executed.map { it.uid })
        assertEquals(DrainSummary(done = 3), summary)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a new operation is due now with no attempts and no error`() = runTest {
        queue().enqueue(op(OperationType.SEND))

        val saved = stored().single()
        assertEquals(t0, saved.createdAt)
        assertEquals(t0, saved.nextAttemptAt)
        assertEquals(0, saved.attempts)
        assertNull(saved.startedAt)
        assertNull(saved.lastError)
    }

    @Test
    fun `accounts do not see each other's operations`() = runTest {
        val other = db.accountDao().insert(account("bea@example.test"))
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        queue.enqueue(op(OperationType.SEND).copy(accountId = other))

        queue.drain(other)

        assertEquals(listOf(other), executed.map { it.accountId })
        assertEquals(1, stored().size)
    }

    // --- merging ---

    @Test
    fun `two flag changes of one message collapse into one with the latest values`() = runTest {
        val queue = queue()
        val first = queue.enqueue(flags(seen = true, flagged = true))
        val second = queue.enqueue(flags(seen = false))

        assertEquals(first, second)
        assertEquals(FlagChange(seen = false, flagged = true).encode(), stored().single().payload)
    }

    @Test
    fun `a later flag change does not erase what it leaves untouched`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true))
        queue.enqueue(flags(flagged = true))

        assertEquals(FlagChange(seen = true, flagged = true).encode(), stored().single().payload)
    }

    @Test
    fun `flag changes of different messages are not merged`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true, uid = 1))
        queue.enqueue(flags(seen = true, uid = 2))

        assertEquals(2, stored().size)
    }

    @Test
    fun `the executor receives the merged flag change`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true))
        queue.enqueue(flags(flagged = true))
        queue.drain(accountId)

        assertEquals(FlagChange(true, true), FlagChange.decode(executed.single().payload))
    }

    @Test
    fun `a delete replaces the flag change queued before it`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true))
        queue.enqueue(op(OperationType.DELETE))

        assertEquals(listOf(OperationType.DELETE), stored().map { it.type })
    }

    @Test
    fun `a delete drops the queued flag changes of that message only`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true, uid = 1))
        queue.enqueue(flags(seen = true, uid = 2))
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 1))

        queue.enqueue(op(OperationType.DELETE, uid = 1))

        assertEquals(
            listOf(
                OperationType.SET_FLAGS to 2L,
                OperationType.ADD_LABEL to 1L,
                OperationType.DELETE to 1L
            ),
            stored().map { it.type to it.uid }
        )
    }

    @Test
    fun `two moves of one message keep one move to the final folder`() = runTest {
        val queue = queue()
        val first = queue.enqueue(move("Archive"))
        val second = queue.enqueue(move("Trash"))

        assertEquals(first, second)
        assertEquals("Trash", stored().single().payload)
    }

    @Test
    fun `moving a message back to where it started cancels the move`() = runTest {
        val queue = queue()
        queue.enqueue(move("Archive"))

        assertNull(queue.enqueue(move("INBOX")))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a move is not merged into a queued change of another kind`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true))
        queue.enqueue(move("Archive"))
        queue.enqueue(op(OperationType.ADD_LABEL))
        queue.enqueue(flags(flagged = true))

        assertEquals(
            listOf(OperationType.SET_FLAGS, OperationType.MOVE, OperationType.ADD_LABEL),
            stored().map { it.type }
        )
        assertEquals(FlagChange(true, true).encode(), stored().first().payload)
    }

    @Test
    fun `a move of another message is not merged`() = runTest {
        val queue = queue()
        queue.enqueue(move("Archive", uid = 1))
        queue.enqueue(move("Trash", uid = 2))

        assertEquals(listOf("Archive", "Trash"), stored().map { it.payload })
    }

    @Test
    fun `an operation already handed to the server is never merged`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true, uid = 1))
        queue.enqueue(move("Archive", uid = 2))
        behaviour = { OperationOutcome.RetryLater("offline") }
        queue.drain(accountId)

        queue.enqueue(flags(seen = false, uid = 1))
        queue.enqueue(move("Trash", uid = 2))

        assertEquals(
            listOf("1-" to 1L, "Archive" to 2L, "0-" to 1L, "Trash" to 2L),
            stored().map { it.payload to it.uid }
        )
    }

    @Test
    fun `a change made while the same kind of change is being sent is queued apart`() = runTest {
        val queue = queue()
        queue.enqueue(flags(seen = true))
        behaviour = {
            queue.enqueue(flags(seen = false))
            OperationOutcome.Done
        }

        queue.drain(accountId)

        assertEquals(FlagChange(seen = true).encode(), executed.single().payload)
        assertEquals(FlagChange(seen = false).encode(), stored().single().payload)
    }

    @Test
    fun `a flag change that loses the race with the sender is queued instead of merged`() =
        runTest {
            val spy = spyk(dao)
            val queue = queue(spy)
            queue.enqueue(flags(seen = true))
            coEvery { spy.replacePayload(any(), any()) } returns 0

            queue.enqueue(flags(seen = false))

            assertEquals(2, stored().size)
        }

    @Test
    fun `a move that loses the race with the sender is queued instead of merged`() = runTest {
        val spy = spyk(dao)
        val queue = queue(spy)
        queue.enqueue(move("Archive"))
        coEvery { spy.replacePayload(any(), any()) } returns 0

        queue.enqueue(move("Trash"))

        assertEquals(listOf("Archive", "Trash"), stored().map { it.payload })
    }

    @Test
    fun `a move back that loses the race with the sender is queued instead of cancelling`() =
        runTest {
            val spy = spyk(dao)
            val queue = queue(spy)
            queue.enqueue(move("Archive"))
            coEvery { spy.deleteUnstarted(any()) } returns 0

            val id = queue.enqueue(move("INBOX"))

            assertNotNull(id)
            assertEquals(listOf("Archive", "INBOX"), stored().map { it.payload })
        }

    // --- outcomes, backoff ---

    @Test
    fun `retry later records the attempt, a deterministic delay and only a reason code`() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(op(OperationType.SEND))!!
            behaviour = { OperationOutcome.RetryLater("timeout") }

            assertEquals(DrainSummary(retryLater = 1), queue.drain(accountId))

            val saved = stored().single()
            assertEquals(id, saved.id)
            assertEquals(1, saved.attempts)
            assertEquals(t0.plusSeconds(30), saved.nextAttemptAt)
            assertEquals("timeout", saved.lastError)
            assertNotNull(saved.startedAt)
            assertFalse(saved.failed)
        }

    @Test
    fun `a long reason is cut to a short code`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        behaviour = { OperationOutcome.RetryLater("x".repeat(500)) }

        queue.drain(accountId)

        assertEquals(64, stored().single().lastError!!.length)
    }

    @Test
    fun `the delay doubles with every failed attempt and an operation waits until it is due`() =
        runTest {
            val queue = queue()
            queue.enqueue(op(OperationType.SEND))
            behaviour = { OperationOutcome.RetryLater("offline") }
            queue.drain(accountId)

            clock.now = t0.plusSeconds(29)
            assertEquals(DrainSummary(), queue.drain(accountId))
            assertEquals(1, executed.size)

            clock.now = t0.plusSeconds(30)
            assertEquals(DrainSummary(retryLater = 1), queue.drain(accountId))

            val saved = stored().single()
            assertEquals(2, executed.size)
            assertEquals(2, saved.attempts)
            assertEquals(clock.now.plusSeconds(60), saved.nextAttemptAt)
        }

    @Test
    fun `the operation that finally succeeds leaves the queue`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        behaviour = { OperationOutcome.RetryLater("offline") }
        queue.drain(accountId)
        behaviour = { OperationOutcome.Done }
        clock.now = t0.plus(Duration.ofHours(1))

        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a failure while sending counts as a failed attempt and is retried`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        behaviour = { error("boom: subject of a secret mail") }

        assertEquals(DrainSummary(retryLater = 1), queue.drain(accountId))

        val saved = stored().single()
        assertEquals("executor_error", saved.lastError)
        assertEquals(1, saved.attempts)
        assertNotNull(saved.startedAt)

        behaviour = { OperationOutcome.Done }
        clock.now = t0.plusSeconds(30)
        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
        assertEquals(2, executed.size)
    }

    // --- rejection, retry, discard ---

    @Test
    fun `a rejected operation is marked failed and not retried automatically`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.MOVE))
        behaviour = { OperationOutcome.Rejected("no_such_folder") }

        assertEquals(DrainSummary(rejected = 1), queue.drain(accountId))

        val saved = stored().single()
        assertTrue(saved.failed)
        assertEquals("no_such_folder", saved.lastError)

        clock.now = t0.plus(Duration.ofDays(1))
        assertEquals(DrainSummary(), queue.drain(accountId))
        assertEquals(1, executed.size)
    }

    @Test
    fun `a failed operation blocks later ones of the same message but not of others`() = runTest {
        val queue = queue()
        val failing = queue.enqueue(op(OperationType.MOVE, uid = 1))!!
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 1))
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 2))
        behaviour = {
            if (it.id == failing) OperationOutcome.Rejected("refused") else OperationOutcome.Done
        }

        assertEquals(DrainSummary(done = 1, rejected = 1), queue.drain(accountId))
        assertEquals(listOf(1L, 2L), executed.map { it.uid })

        // A later pass still keeps the blocked one waiting behind the failed one.
        assertEquals(DrainSummary(), queue.drain(accountId))
        assertEquals(2, stored().size)
        assertEquals(2, executed.size)
    }

    @Test
    fun `an operation waiting for a retry blocks later ones of the same message only`() = runTest {
        val queue = queue()
        val slow = queue.enqueue(op(OperationType.MOVE, uid = 1))!!
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 1))
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 2))
        behaviour = {
            if (it.id == slow) OperationOutcome.RetryLater("offline") else OperationOutcome.Done
        }

        assertEquals(DrainSummary(done = 1, retryLater = 1), queue.drain(accountId))

        assertEquals(listOf(OperationType.MOVE, OperationType.ADD_LABEL), stored().map { it.type })
        assertEquals(listOf(1L, 1L), stored().map { it.uid })
        assertEquals(listOf(1L, 2L), executed.map { it.uid })
    }

    @Test
    fun `an operation that is not due yet blocks the later ones of its message`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.MOVE, uid = 1))
        queue.enqueue(op(OperationType.ADD_LABEL, uid = 1))
        behaviour = { OperationOutcome.RetryLater("offline") }
        queue.drain(accountId)

        behaviour = { OperationOutcome.Done }
        assertEquals(DrainSummary(), queue.drain(accountId))
        assertEquals(1, executed.size)
    }

    @Test
    fun `retrying a failed operation gives it a fresh start`() = runTest {
        val queue = queue()
        val id = queue.enqueue(op(OperationType.MOVE))!!
        behaviour = { OperationOutcome.Rejected("refused") }
        queue.drain(accountId)
        behaviour = { OperationOutcome.Done }
        clock.now = t0.plusSeconds(5)

        queue.retry(id)

        val reset = stored().single()
        assertFalse(reset.failed)
        assertEquals(0, reset.attempts)
        assertNull(reset.lastError)
        assertEquals(clock.now, reset.nextAttemptAt)
        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
    }

    @Test
    fun `retrying an operation that did not fail changes nothing`() = runTest {
        val queue = queue()
        val id = queue.enqueue(op(OperationType.MOVE))!!
        behaviour = { OperationOutcome.RetryLater("offline") }
        queue.drain(accountId)
        val before = stored().single()

        queue.retry(id)

        assertEquals(before, stored().single())
    }

    @Test
    fun `discarding drops an operation and releases the ones behind it`() = runTest {
        val queue = queue()
        val id = queue.enqueue(op(OperationType.MOVE))!!
        queue.enqueue(op(OperationType.ADD_LABEL))
        behaviour = {
            if (it.id == id) OperationOutcome.Rejected("refused") else OperationOutcome.Done
        }
        queue.drain(accountId)

        queue.discard(id)

        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an operation discarded while the drain runs is skipped`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND, uid = 1))
        val doomed = queue.enqueue(op(OperationType.SEND, uid = 2))!!
        behaviour = {
            queue.discard(doomed)
            OperationOutcome.Done
        }

        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
        assertEquals(listOf(1L), executed.map { it.uid })
    }

    // --- crash, cancellation, concurrency ---

    @Test
    fun `cancelling mid-operation keeps it queued as started and it is sent again`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND, uid = 1))
        queue.enqueue(op(OperationType.SEND, uid = 2))
        val entered = CompletableDeferred<Unit>()
        behaviour = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val drain = launch { queue.drain(accountId) }
        entered.await()

        drain.cancelAndJoin()

        val interrupted = stored().first()
        assertNotNull(interrupted.startedAt)
        assertEquals(0, interrupted.attempts)
        assertEquals(2, stored().size)
        assertEquals(1, executed.size)

        behaviour = { OperationOutcome.Done }
        assertEquals(DrainSummary(done = 2), queue.drain(accountId))
        assertEquals(listOf(1L, 1L, 2L), executed.map { it.uid })
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an operation started but never acknowledged is retried`() = runTest {
        val queue = queue()
        val id = queue.enqueue(op(OperationType.SEND))!!
        dao.markStarted(id, t0)

        assertEquals(DrainSummary(done = 1), queue.drain(accountId))
        assertEquals(id, executed.single().id)
    }

    @Test
    fun `drains of one account never run operations at the same time`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        behaviour = {
            running++
            peak = maxOf(peak, running)
            entered.complete(Unit)
            release.await()
            running--
            OperationOutcome.Done
        }
        val first = launch { queue.drain(accountId) }
        entered.await()
        val second = launch { queue.drain(accountId) }

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, peak)
        assertEquals(1, executed.size)
    }

    @Test
    fun `drains of different accounts do not wait for each other`() = runTest {
        val other = db.accountDao().insert(account("bea@example.test"))
        val queue = queue()
        queue.enqueue(op(OperationType.SEND))
        queue.enqueue(op(OperationType.SEND).copy(accountId = other))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        behaviour = {
            if (it.accountId == accountId) {
                entered.complete(Unit)
                release.await()
            }
            OperationOutcome.Done
        }
        val blocked = launch { queue.drain(accountId) }
        entered.await()

        assertEquals(DrainSummary(done = 1), queue.drain(other))

        release.complete(Unit)
        blocked.join()
    }

    // --- observation ---

    @Test
    fun `the pending counts follow the queue`() = runTest {
        val queue = queue()
        queue.observePendingCount(accountId).test {
            assertEquals(0, awaitItem())
            queue.enqueue(op(OperationType.MOVE, uid = 1))
            assertEquals(1, awaitItem())
            queue.enqueue(op(OperationType.MOVE, uid = 2))
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        queue.observePendingCount(accountId, "INBOX", 2).test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        queue.observePendingCount(accountId, "INBOX", 9).test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed operations are counted apart and still pending`() = runTest {
        val queue = queue()
        queue.enqueue(op(OperationType.MOVE, uid = 1))
        queue.enqueue(op(OperationType.MOVE, uid = 2))
        behaviour = {
            if (it.uid == 1L) OperationOutcome.Rejected("refused") else OperationOutcome.Done
        }

        queue.drain(accountId)

        queue.observeFailedCount(accountId).test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        queue.observePendingCount(accountId).test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
