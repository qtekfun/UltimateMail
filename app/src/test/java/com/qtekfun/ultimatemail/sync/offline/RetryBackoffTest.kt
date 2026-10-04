// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Retries follow the backoff of a clock the test controls; a refusal blocks nobody else. */
class RetryBackoffTest {
    private val opened = mutableListOf<EngineHarness>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private suspend fun TestScope.start(): EngineHarness {
        val h = EngineHarness(this)
        opened += h
        h.server.populate()
        h.addAccount()
        h.engine.sync(h.accountId)
        h.server.log.clear()
        return h
    }

    private fun EngineHarness.actions() =
        ConversationActions(messages, queue, marker, QuietScheduler())

    private fun EngineHarness.advance(duration: Duration) {
        clock.now = clock.now.plus(duration)
    }

    private fun EngineHarness.setFlagCalls() = server.logged("setFlags INBOX [1]").size

    private fun networkDownFor(h: EngineHarness, prefix: String) {
        h.server.failure = { name ->
            MailResult.NetworkUnavailable.takeIf { name.startsWith(prefix) }
        }
    }

    @Test
    fun `an operation waits its backoff, doubles it after each failure and finally goes through`() =
        runTest {
            val h = start()
            h.actions().markRead(h.id("INBOX", 1))
            networkDownFor(h, "setFlags")
            val expectedWaits = listOf(30L, 60L, 120L, 240L, 480L)

            expectedWaits.forEachIndexed { index, seconds ->
                h.engine.sync(h.accountId)
                val op = h.operations.all(h.accountId).single()
                assertEquals(index + 1, op.attempts)
                assertEquals(h.clock.now.plusSeconds(seconds), op.nextAttemptAt)
                assertEquals("network", op.lastError)
                // Not due yet: the sync leaves it alone, one second before and after nothing.
                val calls = h.setFlagCalls()
                h.advance(Duration.ofSeconds(seconds - 1))
                h.engine.sync(h.accountId)
                assertEquals(calls, h.setFlagCalls(), "not retried before its time")
                h.advance(Duration.ofSeconds(1))
            }

            h.server.failure = { null }
            h.engine.sync(h.accountId)

            assertTrue(h.operations.all(h.accountId).isEmpty())
            assertTrue(h.server.copy("INBOX", "in-1")!!.flags.seen)
            assertFalse(h.messages.getById(h.id("INBOX", 1))!!.pendingSync)
        }

    @Test
    fun `the wait stops growing at six hours`() = runTest {
        val h = start()
        h.actions().markRead(h.id("INBOX", 1))
        networkDownFor(h, "setFlags")

        repeat(14) {
            h.engine.sync(h.accountId)
            h.advance(Duration.ofHours(7))
        }
        h.engine.sync(h.accountId)

        val op = h.operations.all(h.accountId).single()
        assertEquals(h.clock.now.plusSeconds(6 * 3600), op.nextAttemptAt)
        assertEquals(15, op.attempts)
    }

    @Test
    fun `a refused operation is parked while the others of other messages go through`() = runTest {
        val h = start()
        val actions = h.actions()
        actions.markRead(h.id("INBOX", 1))
        actions.setStarred(h.id("INBOX", 2), true)
        actions.move(listOf(h.id("INBOX", 3)), "Archive")
        h.undoWindowPasses()
        h.server.failure = { name ->
            MailResult.ServerRejected(RejectionKind.NO, permanent = true)
                .takeIf { name.startsWith("setFlags INBOX [1]") }
        }

        h.engine.sync(h.accountId)

        val left = h.operations.all(h.accountId).single()
        assertTrue(left.failed)
        assertEquals("server_rejected", left.lastError)
        assertTrue(h.server.copy("INBOX", "in-2")!!.flags.flagged)
        assertEquals(1, h.server.copies("in-3"))
        assertEquals(null, h.server.copy("INBOX", "in-3"))
        assertEquals(1, h.queue.observeFailedCount(h.accountId).first())
        // Days later the parked operation is still not retried by itself.
        h.advance(Duration.ofDays(3))
        h.server.log.clear()
        h.engine.sync(h.accountId)
        assertEquals(0, h.setFlagCalls())
        assertTrue(h.messages.getById(h.id("INBOX", 1))!!.pendingSync, "still shows as pending")
    }

    @Test
    fun `a later change to the parked message waits behind it until the user retries`() = runTest {
        val h = start()
        h.actions().markRead(h.id("INBOX", 1))
        var refuse = true
        h.server.failure = { name ->
            MailResult.ServerRejected(RejectionKind.NO, permanent = true)
                .takeIf { refuse && name.startsWith("setFlags INBOX [1]") }
        }
        h.engine.sync(h.accountId)
        h.actions().setStarred(h.id("INBOX", 1), true)
        refuse = false
        h.advance(Duration.ofHours(1))
        h.engine.sync(h.accountId)

        assertFalse(h.server.copy("INBOX", "in-1")!!.flags.flagged, "waits behind the parked one")

        h.queue.retry(h.operations.all(h.accountId).first { it.failed }.id)
        h.advance(Duration.ofHours(1))
        h.engine.sync(h.accountId)

        val flags = h.server.copy("INBOX", "in-1")!!.flags
        assertTrue(flags.seen && flags.flagged)
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `discarding a parked change gives the message back to the server's state`() = runTest {
        val h = start()
        h.actions().markRead(h.id("INBOX", 1))
        h.server.failure = { name ->
            MailResult.ServerRejected(RejectionKind.NO, permanent = true)
                .takeIf { name.startsWith("setFlags") }
        }
        h.engine.sync(h.accountId)

        h.queue.discard(h.operations.all(h.accountId).single().id)
        h.server.failure = { null }
        h.engine.sync(h.accountId)

        val row = h.messages.getById(h.id("INBOX", 1))!!
        assertFalse(row.seen, "the server never took the change")
        assertFalse(row.pendingSync)
    }

    @Test
    fun `a send the server refuses for good does not hold back other messages`() = runTest {
        val h = start()
        h.sender.onSend = { MailResult.ServerRejected(RejectionKind.SMTP, permanent = true) }
        val message = OutgoingMessage(
            from = MailAddress("ana@example.test"),
            to = listOf(MailAddress("bob@example.test")),
            subject = "Hi",
            text = "Hello",
            messageId = "<out-1@example.test>"
        )
        h.queue.enqueue(
            NewOperation(
                h.accountId,
                OperationType.SEND,
                "Outbox",
                1,
                OutgoingPayload.encode(message)
            )
        )
        h.actions().markRead(h.id("INBOX", 1))
        h.actions().move(listOf(h.id("INBOX", 2)), "Archive")
        h.undoWindowPasses()

        h.engine.sync(h.accountId)

        assertTrue(h.server.copy("INBOX", "in-1")!!.flags.seen)
        assertEquals(null, h.server.copy("INBOX", "in-2"))
        val left = h.operations.all(h.accountId).single()
        assertEquals(OperationType.SEND, left.type)
        assertTrue(left.failed)
        assertEquals(1, h.sender.sent.size, "not tried again by itself")
        h.advance(Duration.ofDays(1))
        h.engine.sync(h.accountId)
        assertEquals(1, h.sender.sent.size)
    }
}
