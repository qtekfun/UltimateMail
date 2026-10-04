// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerDraftImportTest {
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

    /** A copy in the Drafts folder, on the fake server and in Room; returns its row id. */
    private suspend fun ComposeHarness.serverCopy(
        messageId: String,
        body: String? = "Draft text",
        subject: String = "Plan"
    ): Long {
        val uid = server.deliver(
            "Drafts",
            subject = subject,
            messageId = messageId,
            inReplyTo = "<1@example.test>",
            references = listOf("<0@example.test>", "<1@example.test>")
        )
        val row = message(accountId, uid, folderPath = "Drafts", subject = subject, bodyText = body)
            .copy(
                messageId = messageId,
                toAddresses = listOf("carol@example.test"),
                ccAddresses = listOf("dave@example.test"),
                inReplyTo = "<1@example.test>",
                referenceIds = listOf("<0@example.test>", "<1@example.test>")
            )
        db.messageDao().upsert(listOf(row))
        return checkNotNull(db.messageDao().get(accountId, "Drafts", uid)).id
    }

    private suspend fun ComposeHarness.drain() = engineHarness.queue.drain(accountId)

    private fun ComposeHarness.serverIds() =
        server.folder("Drafts").messages.values.map { it.messageId }

    private suspend fun ComposeHarness.open(row: Long): Draft =
        (serverDrafts.open(row) as ServerDraftOpen.Opened).draft

    private suspend fun ComposeHarness.editing() =
        db.draftDao().observeCount(accountId, DraftState.EDITING).first()

    @Test
    fun `a server draft opens with its text, recipients, subject and threading`() = runTest {
        val h = start()
        val row = h.serverCopy("<abc@mail.example>")

        val draft = h.open(row)

        assertEquals("Draft text", draft.body)
        assertEquals("Plan", draft.subject)
        assertEquals(listOf(MailAddress("carol@example.test")), draft.to)
        assertEquals(listOf(MailAddress("dave@example.test")), draft.cc)
        assertEquals("<1@example.test>", draft.inReplyTo)
        assertEquals(listOf("<0@example.test>", "<1@example.test>"), draft.references)
        assertEquals("<abc@mail.example>", draft.serverMessageId)
        assertEquals(DraftState.EDITING, draft.state)
        assertFalse(draft.dirty)
    }

    @Test
    fun `opening a copy this app made keeps the draft key and shows it once in Drafts`() = runTest {
        val h = start()
        val key = DraftMessageIds.newKey()
        val row = h.serverCopy(DraftMessageIds.forServerCopy(key, "me@example.test"))

        val draft = h.open(row)

        assertEquals(key, draft.key)
        val listed = h.state.observeDrafts(h.accountId).first()
        assertEquals(draft.id, (listed.single() as DraftListItem.Local).draft.id)
    }

    @Test
    fun `a copy from another app is hidden from the server list once it is opened`() = runTest {
        val h = start()
        val row = h.serverCopy("<abc@mail.example>")
        assertTrue(h.state.observeDrafts(h.accountId).first().single() is DraftListItem.OnServer)

        h.open(row)

        assertTrue(h.state.observeDrafts(h.accountId).first().single() is DraftListItem.Local)
    }

    @Test
    fun `opening the same copy twice gives the same draft`() = runTest {
        val h = start()
        val row = h.serverCopy("<abc@mail.example>")

        val first = h.open(row)
        val second = h.open(row)

        assertEquals(first.id, second.id)
        assertEquals(1, h.editing())
    }

    @Test
    fun `a body that is not on the device and cannot be fetched stores nothing`() = runTest {
        val h = start()
        val row = h.serverCopy("<abc@mail.example>", body = null)

        assertEquals(ServerDraftOpen.Unavailable, h.serverDrafts.open(row))

        assertEquals(0, h.editing())
    }

    @Test
    fun `a copy that is gone is unavailable`() = runTest {
        val h = start()

        assertEquals(ServerDraftOpen.Unavailable, h.serverDrafts.open(12345))
    }

    @Test
    fun `saving an edited copy of this app replaces it and leaves one copy`() = runTest {
        val h = start()
        val key = DraftMessageIds.newKey()
        val id = DraftMessageIds.forServerCopy(key, "me@example.test")
        val draft = h.open(h.serverCopy(id))

        h.repository.save(
            draft.id,
            DraftEdit(draft.to, emptyList(), emptyList(), "Plan", "Edited here")
        )
        assertTrue(h.serverSync.request(draft.id, force = true))
        h.drain()

        val after = checkNotNull(h.repository.get(draft.id))
        assertEquals(listOf(after.serverMessageId), h.serverIds())
        assertEquals(key, DraftMessageIds.keyOf(h.serverIds().single()))
        assertTrue(id != h.serverIds().single())
        assertEquals("Edited here", h.server.appendedDrafts.last().text)
    }

    @Test
    fun `saving an edited copy from another app replaces it and leaves one copy`() = runTest {
        val h = start()
        val draft = h.open(h.serverCopy("<abc@mail.example>"))

        h.repository.save(
            draft.id,
            DraftEdit(draft.to, emptyList(), emptyList(), "Plan", "Edited here")
        )
        assertTrue(h.serverSync.request(draft.id, force = true))
        h.drain()

        val after = checkNotNull(h.repository.get(draft.id))
        assertEquals(listOf(after.serverMessageId), h.serverIds())
        assertEquals(draft.key, DraftMessageIds.keyOf(after.serverMessageId))
        assertEquals("Edited here", h.server.appendedDrafts.last().text)
    }

    @Test
    fun `sending an opened copy from another app removes it from the server`() = runTest {
        val h = start()
        val draft = h.open(h.serverCopy("<abc@mail.example>"))

        h.send(draft.id)
        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertTrue(h.serverIds().isEmpty())
        assertNull(h.repository.get(draft.id))
    }

    @Test
    fun `sending an opened copy of this app removes it from the server`() = runTest {
        val h = start()
        val id = DraftMessageIds.forServerCopy(DraftMessageIds.newKey(), "me@example.test")
        val draft = h.open(h.serverCopy(id))

        h.send(draft.id)
        h.drain()

        assertEquals(1, h.engineHarness.sender.sent.size)
        assertTrue(h.serverIds().isEmpty())
    }

    @Test
    fun `discarding an opened copy deletes the server copy`() = runTest {
        val h = start()
        val draft = h.open(h.serverCopy("<abc@mail.example>"))

        h.engine.discard(draft.id)
        h.drain()

        assertTrue(h.serverIds().isEmpty())
    }

    @Test
    fun `the attachments of a server copy come along`() = runTest {
        val h = start()
        val row = h.serverCopy("<abc@mail.example>")
        h.db.attachmentDao().insert(
            listOf(
                AttachmentEntity(
                    messageId = row,
                    partId = "2",
                    fileName = "plan.pdf",
                    mimeType = "application/pdf",
                    size = 2,
                    state = AttachmentState.DOWNLOADED,
                    localPath = "/files/p"
                )
            )
        )
        h.engineHarness.storage.files["/files/p"] = byteArrayOf(1, 2)

        val opened = h.serverDrafts.open(row) as ServerDraftOpen.Opened

        assertEquals(0, opened.attachmentsSkipped)
        assertEquals(listOf("plan.pdf"), h.attachments.list(opened.draft.id).map { it.displayName })
    }
}
