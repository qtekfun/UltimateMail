// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendDraftTest {
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

    private suspend fun ComposeHarness.operations() = db.pendingOperationDao().all(accountId)

    @Test
    fun `sending queues a SEND with everything needed to rebuild the message`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.attachmentSource.put("content://a", "a.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val attached = (h.attachments.add(draft.id, "content://a") as AddAttachmentResult.Added)

        val result = h.send(draft.id) as SendResult.Queued

        val operation = h.operations().single()
        assertEquals(result.operationId, operation.id)
        assertEquals(OperationType.SEND, operation.type)
        assertEquals("" to draft.id, operation.folderPath to operation.uid)
        val queued = OutgoingPayload.decodeQueued(operation.payload)!!
        assertEquals("Subject", queued.message.subject)
        assertEquals("Text", queued.message.text)
        assertEquals("ana@example.test", queued.message.from.address)
        assertEquals("Ana", queued.message.from.name)
        assertEquals(listOf("bob@example.test"), queued.message.to.map { it.address })
        assertEquals(draft.id, queued.draftId)
        assertEquals(draft.key, queued.draftKey)
        assertEquals(1, queued.attachments.size)
        assertEquals(attached.attachment.filePath, queued.attachments.single().path)
        assertEquals("a.pdf", queued.attachments.single().fileName)
        assertEquals("application/pdf", queued.attachments.single().mimeType)
        assertTrue(queued.source == null)
    }

    @Test
    fun `the draft moves to the outbox with the message id of the queued message`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        h.send(draft.id)

        val after = h.repository.get(draft.id)!!
        assertEquals(DraftState.OUTBOX, after.state)
        val queued = OutgoingPayload.decodeQueued(h.operations().single().payload)!!
        assertEquals(after.outgoingMessageId, queued.message.messageId)
        assertTrue(after.outgoingMessageId!!.endsWith("@example.test>"))
        assertEquals(listOf<Long?>(h.accountId), h.scheduler.requests)
    }

    @Test
    fun `the composer can no longer change a draft that was sent`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)

        val change = h.engine.save(
            draft.id,
            DraftEdit(emptyList(), emptyList(), emptyList(), "late", "late")
        )

        assertEquals(DraftChange.NOT_EDITABLE, change)
        assertEquals("Subject", h.repository.get(draft.id)!!.subject)
        assertEquals(
            AddAttachmentResult.NotEditable,
            h.attachments.add(draft.id, "content://anything")
        )
    }

    @Test
    fun `sending the same draft again returns the same operation and queues nothing new`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")

            val first = h.send(draft.id) as SendResult.Queued
            val second = h.send(draft.id) as SendResult.Queued

            assertEquals(first.operationId, second.operationId)
            assertEquals(1, h.operations().size)
        }

    @Test
    fun `waiting server saves of the draft are dropped when it is sent`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id)
        assertEquals(OperationType.SAVE_DRAFT, h.operations().single().type)

        h.send(draft.id)

        assertEquals(listOf(OperationType.SEND), h.operations().map { it.type })
    }

    @Test
    fun `a reply carries its source to be marked as answered, a forward to be marked forwarded`() =
        runTest {
            val h = start()
            val id = h.receive()
            val reply = h.engine.start(
                ComposeRequest(h.accountId, "INBOX", id, ComposeMode.REPLY)
            )!!
            val forward = h.engine.start(
                ComposeRequest(h.accountId, "INBOX", id, ComposeMode.FORWARD)
            )!!
            h.engine.save(
                forward.id,
                DraftEdit(
                    listOf(MailAddress("cy@example.test")),
                    emptyList(),
                    emptyList(),
                    "Fwd",
                    "x"
                )
            )

            h.send(reply.id)
            h.send(forward.id)

            val sources = h.operations().map { OutgoingPayload.decodeQueued(it.payload)!!.source!! }
            assertEquals(listOf(false, true), sources.map { it.forwarded })
            assertEquals(listOf(1L, 1L), sources.map { it.uid })
            assertEquals("<1@example.test>", sources.first().messageId)
            assertEquals(DraftKind.REPLY, h.repository.get(reply.id)!!.kind)
        }

    @Test
    fun `a message that answers nothing carries no source`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        h.send(draft.id)

        assertNull(OutgoingPayload.decodeQueued(h.operations().single().payload)!!.source)
    }

    @Test
    fun `copies count as recipients and an internationalized domain goes in punycode`() = runTest {
        val h = start()
        val draft = h.engine.newMessage(h.accountId)!!
        h.engine.save(
            draft.id,
            DraftEdit(
                emptyList(),
                listOf(MailAddress("cc@example.test")),
                listOf(MailAddress("ana@bücher.example", "Ana")),
                "s",
                "b"
            )
        )

        h.send(draft.id)

        val message = OutgoingPayload.decode(h.operations().single().payload)!!
        assertEquals(listOf("cc@example.test"), message.cc.map { it.address })
        assertEquals(listOf("ana@xn--bcher-kva.example"), message.bcc.map { it.address })
        assertEquals("Ana", message.bcc.single().name)
    }

    @Test
    fun `the thread headers of a reply reach the message`() = runTest {
        val h = start()
        val id = h.receive()
        val reply = h.engine.start(ComposeRequest(h.accountId, "INBOX", id, ComposeMode.REPLY))!!

        h.send(reply.id)

        val message = OutgoingPayload.decode(h.operations().single().payload)!!
        assertEquals("<1@example.test>", message.inReplyTo)
        assertEquals(listOf("<r0@example.test>", "<1@example.test>"), message.references)
        assertEquals("Re: Hello", message.subject)
    }

    @Test
    fun `without recipients nothing is queued and the draft stays editable`() = runTest {
        val h = start()
        val draft = h.engine.newMessage(h.accountId)!!

        assertEquals(SendResult.NoRecipients, h.send(draft.id))

        assertTrue(h.operations().isEmpty())
        assertEquals(DraftState.EDITING, h.repository.get(draft.id)!!.state)
    }

    @Test
    fun `an attachment file that is gone stops the send`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.attachmentSource.put("content://a", "a.pdf", null, byteArrayOf(1))
        val added = h.attachments.add(draft.id, "content://a") as AddAttachmentResult.Added
        h.files.files.remove(added.attachment.filePath)

        assertEquals(SendResult.AttachmentMissing, h.send(draft.id))

        assertTrue(h.operations().isEmpty())
    }

    @Test
    fun `a missing draft, or one whose account was removed with it, is reported`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        assertEquals(SendResult.DraftMissing, h.send(999))
        h.db.accountDao().delete(h.accountId)
        assertNull(h.repository.get(draft.id))
        assertEquals(SendResult.DraftMissing, h.send(draft.id))
    }
}
