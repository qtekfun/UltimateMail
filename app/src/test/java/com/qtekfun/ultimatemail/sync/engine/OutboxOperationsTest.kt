// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.compose.AddAttachmentResult
import com.qtekfun.ultimatemail.domain.compose.ComposeHarness
import com.qtekfun.ultimatemail.domain.compose.Draft
import com.qtekfun.ultimatemail.domain.compose.DraftEdit
import com.qtekfun.ultimatemail.domain.compose.DraftMessageIds
import com.qtekfun.ultimatemail.domain.compose.OutboxChange
import com.qtekfun.ultimatemail.domain.compose.OutboxState
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import java.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The server side of the composer: SEND and SAVE_DRAFT operations that come from a draft. */
class OutboxOperationsTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(entity: AccountEntity? = null): ComposeHarness {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount(entity)
        return h
    }

    /** A server that fails the calls whose log line starts with [prefix]. */
    private fun failOn(prefix: String, failure: MailResult.Failure) =
        { call: String -> failure.takeIf { call.startsWith(prefix) } }

    private suspend fun ComposeHarness.drain() = engineHarness.queue.drain(accountId)

    private fun ComposeHarness.later() {
        clock.now = clock.now.plus(Duration.ofDays(2))
    }

    private suspend fun ComposeHarness.operations() = db.pendingOperationDao().all(accountId)

    /** A reply to message 1 of the Inbox, with a file attached, ready to be sent. */
    private suspend fun ComposeHarness.reply(mode: ComposeMode = ComposeMode.REPLY): Draft {
        server.deliver("INBOX", messageId = "<1@example.test>")
        val id = receive(uid = 1)
        val draft = checkNotNull(
            engine.start(ComposeRequest(accountId, "INBOX", id, mode))
        )
        engine.save(
            draft.id,
            DraftEdit(
                listOf(MailAddress("bob@example.test")),
                emptyList(),
                emptyList(),
                draft.subject,
                "Thanks!\n\n" + draft.body.trimStart()
            )
        )
        attachmentSource.put("content://a", "a.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        assertTrue(attachments.add(draft.id, "content://a") is AddAttachmentResult.Added)
        return checkNotNull(repository.get(draft.id))
    }

    // --- sending ---

    @Test
    fun `a reply goes out with its file, is filed in Sent, marked answered and tidied away`() =
        runTest {
            val h = start()
            val draft = h.reply()
            h.send(draft.id)

            h.drain()

            val sent = h.engineHarness.sender.sent.single()
            assertEquals("Re: Hello", sent.subject)
            assertTrue(sent.text.startsWith("Thanks!"))
            assertEquals("<1@example.test>", sent.inReplyTo)
            assertEquals(listOf("<r0@example.test>", "<1@example.test>"), sent.references)
            assertEquals(listOf<Byte>(1, 2, 3), sent.attachments.single().content.toList())
            assertEquals("a.pdf", sent.attachments.single().fileName)
            // A copy in Sent, with the same Message-ID as the message that went out.
            val (folder, copy) = h.server.appendedSent.single()
            assertEquals("Sent", folder)
            assertEquals(sent.messageId, copy.messageId)
            assertEquals(1, copy.attachments.size)
            // \Answered on the server and in Room.
            assertTrue(h.server.folder("INBOX").messages.getValue(1).flags.answered)
            assertTrue(h.db.messageDao().get(h.accountId, "INBOX", 1)!!.answered)
            // Nothing is left: no draft, no files, no operation.
            assertNull(h.repository.get(draft.id))
            assertEquals(listOf(draft.id), h.files.deletedDrafts)
            assertTrue(h.operations().isEmpty())
        }

    @Test
    fun `a forward marks the message as forwarded and not as answered`() = runTest {
        val h = start()
        val draft = h.reply(ComposeMode.FORWARD)
        h.send(draft.id)

        h.drain()

        assertTrue(("INBOX" to 1L) in h.server.forwarded)
        assertFalse(h.server.folder("INBOX").messages.getValue(1).flags.answered)
        assertFalse(h.db.messageDao().get(h.accountId, "INBOX", 1)!!.answered)
    }

    @Test
    fun `a message that now has another Message-ID under that UID is not marked`() = runTest {
        val h = start()
        val draft = h.reply()
        h.server.folder("INBOX").messages[1] =
            h.server.folder("INBOX").messages.getValue(1).copy(messageId = "<someone-else@x>")
        h.send(draft.id)

        h.drain()

        assertFalse(h.server.folder("INBOX").messages.getValue(1).flags.answered)
        assertEquals(1, h.engineHarness.sender.sent.size)
    }

    @Test
    fun `a message that vanished from the server is simply not marked`() = runTest {
        val h = start()
        val draft = h.reply()
        h.server.expunge("INBOX", 1)
        h.send(draft.id)

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `a reply sent from another account than the one it answers marks nothing`() = runTest {
        val h = start()
        val draft = h.reply()
        val work = h.db.accountDao().insert(account("work@example.test"))
        h.engineHarness.vault.save(work, AccountCredentials(password = "pw"))
        h.db.folderDao().upsert(listOf(folder(work, "Sent", FolderRole.SENT)))
        h.engine.changeSender(draft.id, work)
        h.send(draft.id)

        h.engineHarness.queue.drain(work)

        assertFalse(h.server.folder("INBOX").messages.getValue(1).flags.answered)
        assertEquals("work@example.test", h.engineHarness.sender.sent.single().from.address)
    }

    @Test
    fun `the Sent copy is skipped for Gmail and Microsoft hosts`() = runTest {
        val gmail = account("ana@gmail.com").copy(smtpHost = "smtp.gmail.com")
        val h = start(gmail)
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertTrue(h.server.appendedSent.isEmpty())
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `an account without a Sent folder is sent without a copy`() = runTest {
        val h = start()
        h.db.folderDao().deleteAllExcept(h.accountId, listOf("INBOX", "Drafts"))
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertTrue(h.server.appendedSent.isEmpty())
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `a Sent folder that already has the message gets no second copy`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        // The server took the message on an earlier attempt and the answer was lost.
        h.engineHarness.sender.onSend = { MailResult.Timeout }
        h.drain()
        val id = h.repository.get(draft.id)!!.outgoingMessageId
        h.server.deliver("Sent", messageId = id)
        h.engineHarness.sender.onSend = { error("must not be sent again") }
        h.later()

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertTrue(h.server.appendedSent.isEmpty())
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `the server copy of the draft goes when the message is sent`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        val key = draft.key
        h.server.deliver("Drafts", messageId = "<um-draft.$key.1@example.test>")
        h.server.deliver("Drafts", messageId = "<um-draft.$key.2@example.test>")
        h.server.deliver("Drafts", messageId = "<um-draft.otherkey.1@example.test>")
        h.send(draft.id)

        h.drain()

        assertEquals(
            listOf("<um-draft.otherkey.1@example.test>"),
            h.server.folder("Drafts").messages.values.map { it.messageId }
        )
    }

    @Test
    fun `a permanent refusal keeps the draft in the outbox as failed and it can be edited again`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.send(draft.id)
            h.engineHarness.sender.onSend =
                { MailResult.ServerRejected(RejectionKind.SMTP, true, 550) }

            h.drain()

            assertEquals(DraftState.OUTBOX, h.repository.get(draft.id)!!.state)
            h.state.observeOutbox(h.accountId).test {
                assertEquals(OutboxState.Failed("server_rejected"), awaitItem().single().state)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(h.server.appendedSent.isEmpty())
            assertEquals(OutboxChange.DONE, h.actions.editAgain(draft.id))
        }

    @Test
    fun `a failed send can be retried and then goes out`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        h.engineHarness.sender.onSend = { MailResult.ServerRejected(RejectionKind.SMTP, true, 554) }
        h.drain()
        h.engineHarness.sender.onSend = { null }

        h.actions.retry(draft.id)
        h.drain()

        assertNull(h.repository.get(draft.id))
        assertEquals(2, h.engineHarness.sender.sent.size)
    }

    @Test
    fun `a message waits offline and goes out when the network is back`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        h.engineHarness.connector.failure = MailResult.NetworkUnavailable

        h.drain()

        assertTrue(h.engineHarness.sender.sent.isEmpty())
        assertEquals(DraftState.OUTBOX, h.repository.get(draft.id)!!.state)
        h.engineHarness.connector.failure = null
        h.later()

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `a timeout that did deliver is settled from Sent without sending twice`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        h.engineHarness.sender.onSend = { message ->
            // The server took it but the answer never came.
            h.server.deliver("Sent", messageId = message.messageId)
            MailResult.Timeout
        }

        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertNull(h.repository.get(draft.id))
        assertTrue(h.operations().isEmpty())
        assertTrue(h.server.appendedSent.isEmpty())
    }

    @Test
    fun `when the server accepted the message but filing it fails, it is never sent again`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.send(draft.id)
            h.server.failure =
                failOn("appendSent", MailResult.NetworkUnavailable)

            h.drain()

            assertEquals(1, h.engineHarness.sender.sent.size)
            val kept = h.repository.get(draft.id)!!
            assertNotNull(kept.smtpAcceptedAt)
            h.state.observeOutbox(h.accountId).test {
                assertEquals(OutboxState.Sending, awaitItem().single().state)
                cancelAndIgnoreRemainingEvents()
            }
            // Neither editing nor discarding is possible any more.
            assertEquals(OutboxChange.MAY_BE_SENT, h.actions.editAgain(draft.id))
            h.server.failure = { null }
            h.engineHarness.sender.onSend = { error("must not be sent again") }
            h.later()

            h.drain()

            assertEquals(1, h.engineHarness.sender.sent.size)
            assertEquals(1, h.server.appendedSent.size)
            assertNull(h.repository.get(draft.id))
            assertTrue(h.operations().isEmpty())
        }

    @Test
    fun `tidying up that keeps failing is given up on and the message leaves the outbox`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.send(draft.id)
            h.server.failure =
                failOn("appendSent", MailResult.Timeout)

            repeat(10) {
                h.drain()
                h.later()
            }

            assertEquals(1, h.engineHarness.sender.sent.size)
            assertNull(h.repository.get(draft.id))
            assertTrue(h.operations().isEmpty())
            assertTrue(h.server.appendedSent.isEmpty())
        }

    @Test
    fun `a refusal while filing or tidying does not hold the message back`() = runTest {
        val h = start()
        val draft = h.reply()
        h.send(draft.id)
        h.server.failure = { name ->
            MailResult.ServerRejected(RejectionKind.NO, true).takeIf {
                name.startsWith("appendSent") || name.startsWith("setFlags")
            }
        }

        h.drain()

        assertNull(h.repository.get(draft.id))
        assertTrue(h.operations().isEmpty())
    }

    @Test
    fun `a file that disappeared from the outbox fails the send for good`() = runTest {
        val h = start()
        val draft = h.reply()
        h.send(draft.id)
        h.files.files.clear()

        h.drain()

        assertTrue(h.engineHarness.sender.sent.isEmpty())
        h.state.observeOutbox(h.accountId).test {
            assertEquals(OutboxState.Failed("attachment_missing"), awaitItem().single().state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a draft that was discarded meanwhile is not sent`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.send(draft.id)
        h.repository.delete(draft.id)

        h.drain()

        assertTrue(h.engineHarness.sender.sent.isEmpty())
        assertTrue(h.operations().isEmpty())
    }

    // --- saving drafts on the server ---

    private suspend fun ComposeHarness.upload(draft: Draft): Draft {
        assertTrue(serverSync.request(draft.id, force = true))
        drain()
        return checkNotNull(repository.get(draft.id))
    }

    private fun ComposeHarness.serverDrafts() =
        server.folder("Drafts").messages.values.map { it.messageId }

    @Test
    fun `the first save puts the draft in Drafts and records the copy`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")

        val after = h.upload(draft)

        assertEquals(1, h.server.folder("Drafts").messages.size)
        assertEquals(h.serverDrafts().single(), after.serverMessageId)
        assertEquals(draft.key, DraftMessageIds.keyOf(after.serverMessageId))
        assertFalse(after.dirty)
        assertTrue(h.server.folder("Drafts").messages.values.single().flags.draft)
        assertTrue(h.operations().isEmpty())
    }

    @Test
    fun `a later save replaces the previous copy`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        val first = h.upload(draft)
        h.repository.save(
            draft.id,
            DraftEdit(draft.to, emptyList(), emptyList(), "Subject", "Edited text")
        )
        h.later()

        val second = h.upload(draft)

        assertEquals(1, h.server.folder("Drafts").messages.size)
        assertEquals(second.serverMessageId, h.serverDrafts().single())
        assertTrue(first.serverMessageId != second.serverMessageId)
        assertEquals("Edited text", h.server.appendedDrafts.last().text)
    }

    @Test
    fun `a save whose answer was lost is found on the server and not uploaded twice`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id, force = true)
        // An earlier attempt stored the draft, then the connection dropped before it was recorded.
        val payload = OutgoingPayload.decodeQueued(h.operations().single().payload)!!
        h.server.deliver("Drafts", messageId = payload.message.messageId)

        h.drain()

        assertEquals(1, h.server.folder("Drafts").messages.size)
        assertTrue(h.server.appendedDrafts.isEmpty())
        assertEquals(payload.message.messageId, h.repository.get(draft.id)!!.serverMessageId)
        assertFalse(h.repository.get(draft.id)!!.dirty)
    }

    @Test
    fun `a failure while uploading is retried later`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id, force = true)
        h.server.failure = failOn("appendDraft", MailResult.Timeout)

        h.drain()

        assertEquals("timeout", h.operations().single().lastError)
        h.server.failure = { null }
        h.later()
        h.drain()
        assertTrue(h.operations().isEmpty())
        assertEquals(1, h.server.folder("Drafts").messages.size)
    }

    @Test
    fun `a failure while removing the old copy is retried and leaves one copy at the end`() =
        runTest {
            val h = start()
            val draft = h.writeTo("bob@example.test")
            h.upload(draft)
            h.repository.save(draft.id, DraftEdit(draft.to, emptyList(), emptyList(), "S", "v2"))
            h.later()
            h.serverSync.request(draft.id, force = true)
            h.server.failure =
                failOn("delete Drafts", MailResult.Timeout)

            h.drain()

            assertEquals(2, h.server.folder("Drafts").messages.size)
            h.server.failure = { null }
            h.later()
            h.drain()

            assertEquals(1, h.server.folder("Drafts").messages.size)
            assertFalse(h.repository.get(draft.id)!!.dirty)
        }

    @Test
    fun `a draft edited on two devices keeps both versions and tells the user`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        val uploaded = h.upload(draft)
        // The other device saved its own version of the same draft.
        val theirs = "<um-draft.${draft.key}.fromphone@example.test>"
        h.server.deliver("Drafts", messageId = theirs)
        h.repository.save(draft.id, DraftEdit(draft.to, emptyList(), emptyList(), "S", "my edit"))
        h.later()

        h.engineHarness.notices.notices.test {
            h.serverSync.request(draft.id, force = true)
            h.drain()

            assertEquals(SyncNotice.DraftConflict(h.accountId, draft.key), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val after = h.repository.get(draft.id)!!
        assertTrue(after.key != draft.key)
        assertEquals(after.key, DraftMessageIds.keyOf(after.serverMessageId))
        // The other device's copy, the old copy of this device and the new fork all stay.
        val ids = h.serverDrafts()
        assertTrue(theirs in ids)
        assertTrue(uploaded.serverMessageId in ids)
        assertTrue(after.serverMessageId in ids)
        assertEquals("my edit", h.server.appendedDrafts.last().text)
        assertFalse(after.dirty)
    }

    @Test
    fun `a fork whose answer was lost does not fork again`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.upload(draft)
        h.server.deliver("Drafts", messageId = "<um-draft.${draft.key}.fromphone@example.test>")
        h.repository.save(draft.id, DraftEdit(draft.to, emptyList(), emptyList(), "S", "my edit"))
        h.later()
        h.serverSync.request(draft.id, force = true)

        h.engineHarness.notices.notices.test {
            h.server.failure = failOn("appendDraft", MailResult.Timeout)
            h.drain()
            assertEquals(SyncNotice.DraftConflict(h.accountId, draft.key), awaitItem())
            val forkedKey = h.repository.get(draft.id)!!.key
            h.server.failure = { null }
            h.later()

            h.drain()

            expectNoEvents()
            val after = h.repository.get(draft.id)!!
            assertEquals(forkedKey, after.key)
            assertEquals(forkedKey, DraftMessageIds.keyOf(after.serverMessageId))
            assertEquals(1, h.server.appendedDrafts.count { it.text == "my edit" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a draft deleted on the server while edited here is uploaded again`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.upload(draft)
        h.server.folder("Drafts").messages.keys.toList().forEach { h.server.expunge("Drafts", it) }
        h.repository.save(draft.id, DraftEdit(draft.to, emptyList(), emptyList(), "S", "again"))
        h.later()

        h.upload(draft)

        assertEquals(1, h.server.folder("Drafts").messages.size)
    }

    @Test
    fun `nothing is saved for a draft that was discarded or already sent`() = runTest {
        val h = start()
        val gone = h.writeTo("bob@example.test")
        val sent = h.writeTo("cy@example.test")
        h.serverSync.request(gone.id, force = true)
        h.serverSync.request(sent.id, force = true)
        h.db.draftDao().delete(gone.id)
        h.db.draftDao().update(
            h.db.draftDao().get(sent.id)!!.copy(state = DraftState.OUTBOX)
        )

        h.drain()

        assertTrue(h.server.folder("Drafts").messages.isEmpty())
        assertTrue(h.operations().isEmpty())
    }

    @Test
    fun `an account without a Drafts folder has nothing to save`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id, force = true)
        h.db.folderDao().deleteAllExcept(h.accountId, listOf("INBOX"))

        h.drain()

        assertTrue(h.operations().isEmpty())
        assertTrue(h.server.appendedDrafts.isEmpty())
    }

    @Test
    fun `a Drafts folder that cannot be read is retried later`() = runTest {
        val h = start()
        val draft = h.writeTo("bob@example.test")
        h.serverSync.request(draft.id, force = true)
        h.server.failure =
            failOn("folderStatus Drafts", MailResult.NetworkUnavailable)

        h.drain()

        assertEquals("network", h.operations().single().lastError)
    }

    @Test
    fun `a save made before an update still runs in the old format`() = runTest {
        val h = start()
        h.engineHarness.operations.enqueue(
            PendingOperationEntity(
                accountId = h.accountId,
                type = OperationType.SAVE_DRAFT,
                folderPath = "Drafts",
                uid = 0,
                payload = OutgoingPayload.encode(
                    OutgoingMessage(
                        from = MailAddress("ana@example.test"),
                        to = emptyList(),
                        subject = "old",
                        text = "old",
                        messageId = "<old@x>"
                    )
                ),
                createdAt = h.clock.instant()
            )
        )

        h.drain()

        assertEquals("old", h.server.appendedDrafts.single().subject)
        assertTrue(h.operations().isEmpty())
    }
}
