// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboxTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): ComposeHarness {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount()
        return h
    }

    /** A draft sent into the outbox; returns its draft id and SEND operation id. */
    private suspend fun ComposeHarness.queued(): Pair<Long, Long> {
        val draft = writeTo("bob@example.test")
        val result = send(draft.id) as SendResult.Queued
        return draft.id to result.operationId
    }

    private suspend fun ComposeHarness.stateOf(draftId: Long): OutboxState =
        state.observeOutbox(accountId).let { flow ->
            var found: OutboxState? = null
            flow.test {
                found = awaitItem().first { it.draft.id == draftId }.state
                cancelAndIgnoreRemainingEvents()
            }
            checkNotNull(found)
        }

    // --- states ---

    @Test
    fun `a message nobody has tried to send yet is queued`() = runTest {
        val h = start()
        val (draft, _) = h.queued()

        assertEquals(OutboxState.Queued(0, null, null), h.stateOf(draft))
    }

    @Test
    fun `a message handed to the server without an answer yet is sending`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markStarted(op, h.clock.instant())

        assertEquals(OutboxState.Sending, h.stateOf(draft))
    }

    @Test
    fun `a message waiting for a retry is queued with the reason and the next attempt`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        val next = Instant.ofEpochSecond(1_700_100_900)
        h.db.pendingOperationDao().markStarted(op, h.clock.instant())
        h.db.pendingOperationDao().markRetryLater(op, 2, next, "network")

        assertEquals(OutboxState.Queued(2, next, "network"), h.stateOf(draft))
    }

    @Test
    fun `a message the server refused for good is failed with the reason code`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markFailed(op, "server_rejected")

        assertEquals(OutboxState.Failed("server_rejected"), h.stateOf(draft))
    }

    @Test
    fun `a failure without a reason is still shown as failed`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().delete(op)
        h.db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = h.accountId,
                type = OperationType.SEND,
                folderPath = "",
                uid = draft,
                payload = "p",
                createdAt = h.clock.instant(),
                failed = true
            )
        )

        assertEquals(OutboxState.Failed("unknown"), h.stateOf(draft))
    }

    @Test
    fun `a message the SMTP server took is sending whatever its operation says`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markRetryLater(op, 1, h.clock.instant(), "network")
        h.db.draftDao().markSmtpAccepted(draft, h.clock.instant())

        assertEquals(OutboxState.Sending, h.stateOf(draft))
    }

    @Test
    fun `an outbox draft without its operation is shown as failed so that it can be edited`() =
        runTest {
            val h = start()
            val (draft, op) = h.queued()
            h.db.pendingOperationDao().delete(op)

            assertEquals(OutboxState.Failed("no_operation"), h.stateOf(draft))
        }

    // --- actions ---

    @Test
    fun `retrying a failed send gives it a fresh start and asks for a sync`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markFailed(op, "server_rejected")
        h.scheduler.requests.clear()

        assertEquals(OutboxChange.DONE, h.actions.retry(draft))

        val operation = h.db.pendingOperationDao().get(op)!!
        assertEquals(false, operation.failed)
        assertNull(operation.lastError)
        assertEquals(listOf<Long?>(h.accountId), h.scheduler.requests)
    }

    @Test
    fun `retrying what is not in the outbox finds nothing`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        assertEquals(OutboxChange.MISSING, h.actions.retry(999))
        assertEquals(OutboxChange.MISSING, h.actions.retry(draft.id))
    }

    @Test
    fun `a failed message can be taken back to the composer and gets a new message id`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markStarted(op, h.clock.instant())
        h.db.pendingOperationDao().markFailed(op, "server_rejected")
        val before = h.repository.get(draft)!!

        assertEquals(OutboxChange.DONE, h.actions.editAgain(draft))

        val after = h.repository.get(draft)!!
        assertEquals(DraftState.EDITING, after.state)
        assertNull(after.outgoingMessageId)
        assertTrue(after.dirty)
        assertEquals(before.revision + 1, after.revision)
        assertNull(h.db.pendingOperationDao().get(op))
        // The composer owns it again.
        assertEquals(
            DraftChange.SAVED,
            h.engine.save(draft, DraftEdit(emptyList(), emptyList(), emptyList(), "s", "b"))
        )
    }

    @Test
    fun `a message that never reached the server can be taken back, for example while offline`() =
        runTest {
            val h = start()
            val (draft, op) = h.queued()

            assertEquals(OutboxChange.DONE, h.actions.editAgain(draft))

            assertNull(h.db.pendingOperationDao().get(op))
            assertEquals(DraftState.EDITING, h.repository.get(draft)!!.state)
        }

    @Test
    fun `a message that may already have gone out cannot be taken back or discarded`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markStarted(op, h.clock.instant())
        h.db.pendingOperationDao().markRetryLater(op, 1, h.clock.instant(), "timeout")

        assertEquals(OutboxChange.MAY_BE_SENT, h.actions.editAgain(draft))
        assertEquals(OutboxChange.MAY_BE_SENT, h.actions.discard(draft))

        assertNotNull(h.db.pendingOperationDao().get(op))
        assertEquals(DraftState.OUTBOX, h.repository.get(draft)!!.state)
    }

    @Test
    fun `a message the SMTP server already accepted cannot be taken back or discarded`() = runTest {
        val h = start()
        val (draft, _) = h.queued()
        h.db.draftDao().markSmtpAccepted(draft, h.clock.instant())

        assertEquals(OutboxChange.MAY_BE_SENT, h.actions.editAgain(draft))
        assertEquals(OutboxChange.MAY_BE_SENT, h.actions.discard(draft))
    }

    @Test
    fun `an outbox draft whose operation vanished can be edited or discarded`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().delete(op)

        assertEquals(OutboxChange.DONE, h.actions.editAgain(draft))
        val (second, secondOp) = h.queued()
        h.db.pendingOperationDao().delete(secondOp)
        assertEquals(OutboxChange.DONE, h.actions.discard(second))
        assertNull(h.repository.get(second))
    }

    @Test
    fun `discarding a failed message removes it with its files`() = runTest {
        val h = start()
        val (draft, op) = h.queued()
        h.db.pendingOperationDao().markFailed(op, "server_rejected")

        assertEquals(OutboxChange.DONE, h.actions.discard(draft))

        assertNull(h.repository.get(draft))
        assertNull(h.db.pendingOperationDao().get(op))
        assertEquals(listOf(draft), h.files.deletedDrafts)
    }

    @Test
    fun `editing or discarding what is not in the outbox finds nothing`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        assertEquals(OutboxChange.MISSING, h.actions.editAgain(draft.id))
        assertEquals(OutboxChange.MISSING, h.actions.discard(draft.id))
        assertEquals(OutboxChange.MISSING, h.actions.discard(999))
    }

    // --- lists and counts ---

    @Test
    fun `the drafts list has local drafts and server drafts that are not their copies`() = runTest {
        val h = start()
        val mine = h.writeTo("bob@example.test")
        val sent = h.writeTo("cy@example.test")
        h.send(sent.id)
        val copy = "<um-draft.${mine.key}.1@example.test>"
        h.db.draftDao().markUploaded(mine.id, copy, 0)
        h.clock.now = Instant.ofEpochSecond(1_700_200_000)
        h.db.messageDao().upsert(
            listOf(
                message(
                    h.accountId,
                    1,
                    "Drafts",
                    sentAt = 1_700_000_000_000
                ).copy(messageId = copy),
                message(h.accountId, 2, "Drafts", sentAt = 1_600_000_000_000)
                    .copy(messageId = "<um-draft.${mine.key}.0@example.test>"),
                message(
                    h.accountId,
                    3,
                    "Drafts",
                    sentAt = 1_800_000_000_000,
                    subject = "Other device"
                ),
                message(h.accountId, 4, "INBOX", sentAt = 1_900_000_000_000)
            )
        )

        h.state.observeDrafts(h.accountId).test {
            val items = awaitItem()
            assertEquals(2, items.size)
            // Newest first: the server draft of the other device is newer than the local one.
            assertTrue(items[0] is DraftListItem.OnServer)
            assertEquals("Other device", (items[0] as DraftListItem.OnServer).draft.subject)
            assertEquals(mine.id, (items[1] as DraftListItem.Local).draft.id)
            assertEquals(h.accountId, items[0].accountId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the drafts of all accounts are listed together`() = runTest {
        val h = start()
        val other = h.db.accountDao().insert(account("other@example.test"))
        h.writeTo("bob@example.test")
        h.engine.newMessage(other)

        h.state.observeDrafts().test {
            assertEquals(setOf(h.accountId, other), awaitItem().map { it.accountId }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitValue(expected: T) {
        while (awaitItem() != expected) {
            // Intermediate states, while the queue and the draft change one after the other.
        }
    }

    @Test
    fun `the counts follow sending, failing and discarding`() = runTest {
        val h = start()
        h.state.observeCounts(h.accountId).test {
            awaitValue(ComposeCounts(0, 0, 0))
            val draft = h.writeTo("bob@example.test")
            awaitValue(ComposeCounts(1, 0, 0))
            val queued = h.send(draft.id) as SendResult.Queued
            awaitValue(ComposeCounts(0, 1, 0))
            h.db.pendingOperationDao().markFailed(queued.operationId, "server_rejected")
            awaitValue(ComposeCounts(0, 1, 1))
            h.actions.discard(draft.id)
            awaitValue(ComposeCounts(0, 0, 0))
            cancelAndIgnoreRemainingEvents()
        }
        h.state.observeOutboxCount(h.accountId).test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the outbox lists the oldest first and across accounts when asked`() = runTest {
        val h = start()
        val other = h.db.accountDao().insert(account("other@example.test"))
        val first = h.writeTo("bob@example.test")
        h.clock.now = h.clock.now.plusSeconds(10)
        val secondDraft = checkNotNull(h.engine.newMessage(other))
        h.engine.save(
            secondDraft.id,
            DraftEdit(
                listOf(MailAddress("x@example.test")),
                emptyList(),
                emptyList(),
                "s",
                "b"
            )
        )
        h.send(secondDraft.id)
        h.send(first.id)

        h.state.observeOutbox().test {
            assertEquals(listOf(first.id, secondDraft.id), awaitItem().map { it.draft.id })
            cancelAndIgnoreRemainingEvents()
        }
        h.state.observeOutbox(other).test {
            assertEquals(listOf(secondDraft.id), awaitItem().map { it.draft.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            OperationType.SEND,
            h.db.pendingOperationDao().all(other).single().type
        )
    }
}
