// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwardAttachmentsTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private class Setup(val h: ComposeHarness, val messageRow: Long, val draft: Long)

    /** A received message 1 with the given attachment rows, and a forward draft of it. */
    private suspend fun TestScope.start(vararg parts: AttachmentEntity): Setup {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount()
        val row = h.receive(uid = 1)
        h.db.attachmentDao().insert(parts.map { it.copy(messageId = row) })
        val draft = checkNotNull(
            h.engine.start(ComposeRequest(h.accountId, "INBOX", row, ComposeMode.FORWARD))
        )
        return Setup(h, row, draft.id)
    }

    private fun part(
        id: String,
        name: String,
        inline: Boolean = false,
        state: AttachmentState = AttachmentState.REMOTE,
        localPath: String? = null
    ) = AttachmentEntity(
        messageId = 0,
        partId = id,
        fileName = name,
        mimeType = "application/pdf",
        size = 3,
        state = state,
        localPath = localPath,
        contentId = if (inline) "c-$id" else null,
        inline = inline
    )

    private fun downloaded(id: String, name: String, path: String) =
        part(id, name, state = AttachmentState.DOWNLOADED, localPath = path)

    @Test
    fun `an attachment already on the device is copied into the draft`() = runTest {
        val s = start(downloaded("2", "a.pdf", "/files/x"))
        s.h.engineHarness.storage.files["/files/x"] = byteArrayOf(7, 8, 9)

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow)

        assertEquals(ForwardAttachmentsResult(attached = 1, skipped = 0), result)
        val chip = s.h.attachments.list(s.draft).single()
        assertEquals("a.pdf", chip.displayName)
        assertEquals(listOf<Byte>(7, 8, 9), s.h.files.files.getValue(chip.filePath).toList())
        assertTrue(s.h.server.logged("fetchAttachment").isEmpty())
    }

    @Test
    fun `an attachment not downloaded yet is fetched first and then attached`() = runTest {
        val s = start(part("2", "b.pdf"))
        s.h.server.folder("INBOX").attachments[1L to "2"] = byteArrayOf(1, 2, 3, 4)

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow)

        assertTrue(result.allAttached)
        assertEquals(1, s.h.server.logged("fetchAttachment").size)
        val chip = s.h.attachments.list(s.draft).single()
        assertEquals(4L, chip.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), s.h.files.files.getValue(chip.filePath).toList())
    }

    @Test
    fun `without a connection the missing files are skipped and the rest still come`() = runTest {
        val s = start(downloaded("2", "here.pdf", "/files/y"), part("3", "far.pdf"))
        s.h.engineHarness.storage.files["/files/y"] = byteArrayOf(1)
        s.h.server.failure =
            { if (it.startsWith("fetchAttachment")) MailResult.NetworkUnavailable else null }

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow)

        assertEquals(ForwardAttachmentsResult(attached = 1, skipped = 1), result)
        assertEquals(listOf("here.pdf"), s.h.attachments.list(s.draft).map { it.displayName })
    }

    @Test
    fun `images the body shows inline are not attached`() = runTest {
        val s = start(part("2", "logo.png", inline = true), downloaded("3", "real.pdf", "/files/z"))
        s.h.engineHarness.storage.files["/files/z"] = byteArrayOf(1)

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow)

        assertEquals(ForwardAttachmentsResult(attached = 1, skipped = 0), result)
        assertEquals(listOf("real.pdf"), s.h.attachments.list(s.draft).map { it.displayName })
    }

    @Test
    fun `the size limit of the draft is respected and what does not fit is skipped`() = runTest {
        val s = start(part("2", "huge.bin"), part("3", "small.bin"))
        s.h.server.folder("INBOX").attachments[1L to "2"] =
            ByteArray((AttachmentLimits.MAX_BYTES + 1).toInt())
        s.h.server.folder("INBOX").attachments[1L to "3"] = byteArrayOf(1)

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow)

        assertEquals(ForwardAttachmentsResult(attached = 1, skipped = 1), result)
        assertEquals(listOf("small.bin"), s.h.attachments.list(s.draft).map { it.displayName })
    }

    @Test
    fun `when the time for downloads is up nothing more is fetched`() = runTest {
        val s = start(part("2", "slow.pdf"))
        s.h.server.folder("INBOX").attachments[1L to "2"] = byteArrayOf(1)

        val result = s.h.forwardAttachments.attach(s.draft, s.messageRow, timeoutMillis = 0)

        assertEquals(ForwardAttachmentsResult(attached = 0, skipped = 1), result)
        assertTrue(s.h.attachments.list(s.draft).isEmpty())
    }

    @Test
    fun `removing a chip deletes the copy but not the received message's file`() = runTest {
        val s = start(downloaded("2", "a.pdf", "/files/q"))
        s.h.engineHarness.storage.files["/files/q"] = byteArrayOf(5)
        s.h.forwardAttachments.attach(s.draft, s.messageRow)
        val chip = s.h.attachments.list(s.draft).single()

        s.h.attachments.remove(chip.id)

        assertTrue(s.h.attachments.list(s.draft).isEmpty())
        assertTrue(chip.filePath !in s.h.files.files)
        assertTrue("/files/q" in s.h.engineHarness.storage.files)
    }
}
