// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The connection drops while the queue is pushed: nothing lost, nothing applied twice. */
class PushDropsTest {
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
        h.offlineDownloads.setEnabled(h.accountId, true)
        h.engine.sync(h.accountId)
        return h
    }

    private suspend fun TestScope.reference(): Pair<RoomState, Map<String, List<String>>> {
        val h = start()
        h.userActs()
        h.engine.sync(h.accountId)
        return h.roomState() to h.server.contents()
    }

    private suspend fun TestScope.sweep(afterApplied: Boolean) {
        val (expected, expectedServer) = reference()
        val probe = start()
        probe.userActs()
        probe.server.log.clear()
        probe.engine.sync(probe.accountId)
        val total = probe.server.calls().size
        for (n in 1..total) {
            val h = start()
            val net = FlakyNetwork(h)
            h.userActs()
            if (afterApplied) net.dropAfter(n) else net.dropBefore(n)
            val cut = h.engine.sync(h.accountId)
            assertTrue(net.isDown, "drop $n/$total was not reached")
            assertTrue(cut is AccountSyncResult.Failed, "drop $n/$total: $cut")
            net.restore()
            h.syncUntilDone()
            val label = if (afterApplied) "lost answer" else "cut before"
            assertEquals(expectedServer, h.server.contents(), "server, $label at $n/$total")
            assertEquals(expected, h.roomState(), "Room, $label at $n/$total")
            assertTrue(h.operations.all(h.accountId).isEmpty(), "queue drained, $label at $n")
        }
    }

    @Test
    fun `a cut before the server applied an operation loses none and applies each once`() =
        runTest { sweep(afterApplied = false) }

    @Test
    fun `a cut after the server applied it but before the answer repeats nothing`() =
        runTest { sweep(afterApplied = true) }

    private val outgoing = OutgoingMessage(
        from = MailAddress("ana@example.test"),
        to = listOf(MailAddress("bob@example.test")),
        subject = "Hi",
        text = "Hello",
        messageId = "<out-1@example.test>"
    )

    private suspend fun EngineHarness.queueSend(draftId: Long, message: OutgoingMessage) {
        queue.enqueue(
            NewOperation(
                accountId,
                OperationType.SEND,
                "Outbox",
                draftId,
                OutgoingPayload.encode(message)
            )
        )
    }

    private fun EngineHarness.sentCopies(id: String) =
        server.folder("Sent").messages.values.count { it.messageId == id }

    @Test
    fun `a send whose SMTP answer is lost is confirmed in Sent and never repeated`() = runTest {
        val h = start()
        // The server accepts and files it in Sent, then the connection dies before the reply.
        h.sender.onSend = {
            h.server.deliver("Sent", messageId = it.messageId)
            MailResult.NetworkUnavailable
        }
        h.queueSend(1, outgoing)

        h.engine.sync(h.accountId)
        h.waitOutBackoff()
        h.engine.sync(h.accountId)

        assertEquals(1, h.sentCopies("<out-1@example.test>"))
        assertEquals(1, h.sender.sent.size, "SMTP was asked once")
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `a send refused before SMTP took it is sent exactly once when the network returns`() =
        runTest {
            val h = start()
            var up = false
            h.sender.onSend = {
                if (up) {
                    h.server.deliver("Sent", messageId = it.messageId)
                    null
                } else {
                    MailResult.NetworkUnavailable
                }
            }
            h.queueSend(1, outgoing)

            val first = h.engine.sync(h.accountId)
            assertEquals(1, h.operations.all(h.accountId).size, "the send stays queued")
            assertEquals(0, h.sentCopies("<out-1@example.test>"))
            up = true
            h.waitOutBackoff()
            h.engine.sync(h.accountId)
            h.waitOutBackoff()
            h.engine.sync(h.accountId)

            assertTrue(first is AccountSyncResult.Synced)
            assertEquals(1, h.sentCopies("<out-1@example.test>"))
            assertTrue(h.operations.all(h.accountId).isEmpty())
        }

    @Test
    fun `when Sent cannot be read after a failed send the message is not sent blind again`() =
        runTest {
            val h = start()
            val net = FlakyNetwork(h)
            h.sender.onSend = {
                net.cut()
                MailResult.NetworkUnavailable
            }
            h.queueSend(1, outgoing)

            h.engine.sync(h.accountId)
            net.restore()
            h.sender.onSend = { error("must not be sent while Sent is unconfirmed") }
            h.server.deliver("Sent", messageId = "<out-1@example.test>")
            h.syncUntilDone()

            assertEquals(1, h.sender.sent.size)
            assertTrue(h.operations.all(h.accountId).isEmpty())
        }
}
