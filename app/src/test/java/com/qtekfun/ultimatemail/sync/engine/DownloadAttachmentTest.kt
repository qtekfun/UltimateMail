// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.FakeAttachmentStorage
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadAttachmentTest {
    private var harness: EngineHarness? = null
    private val storage = FakeAttachmentStorage()

    @AfterEach
    fun close() {
        harness?.close()
    }

    private class Setup(
        val h: EngineHarness,
        val download: DownloadAttachment,
        val messageId: Long,
        val pdfId: Long,
        val imageId: Long
    )

    private suspend fun TestScope.start(html: String? = "<img src=\"cid:logo@x\">"): Setup {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.server.deliver("INBOX")
        h.server.folder("INBOX").attachments[1L to "2"] = byteArrayOf(1, 2, 3)
        h.server.folder("INBOX").attachments[1L to "3"] = byteArrayOf(9)
        h.addAccount()
        h.engine.sync(h.accountId)
        val messageId = h.messages.get(h.accountId, "INBOX", 1)!!.id
        h.messages.setBody(h.accountId, "INBOX", 1, "text", html)
        h.db.attachmentDao().insert(
            listOf(
                AttachmentEntity(
                    messageId = messageId,
                    partId = "2",
                    fileName = "a.pdf",
                    mimeType = "application/pdf",
                    size = 3
                ),
                AttachmentEntity(
                    messageId = messageId,
                    partId = "3",
                    fileName = "logo.png",
                    mimeType = "image/png",
                    size = 1,
                    contentId = "logo@x",
                    inline = true
                )
            )
        )
        val all = h.db.attachmentDao().listFor(messageId)
        h.server.log.clear()
        return Setup(
            h,
            DownloadAttachment(h.db.attachmentDao(), h.messages, h.sessions, storage),
            messageId,
            all[0].id,
            all[1].id
        )
    }

    private suspend fun Setup.stored(id: Long) = h.db.attachmentDao().get(id)!!

    @Test
    fun `an attachment is fetched, stored and recorded as downloaded`() = runTest {
        val s = start()

        val result = s.download(s.pdfId)

        val path = (result as DownloadResult.Ready).path
        assertEquals(listOf<Byte>(1, 2, 3), storage.files.getValue(path).toList())
        assertEquals(AttachmentState.DOWNLOADED, s.stored(s.pdfId).state)
        assertEquals(path, s.stored(s.pdfId).localPath)
    }

    @Test
    fun `a second request uses the file on the device`() = runTest {
        val s = start()
        s.download(s.pdfId)

        val again = s.download(s.pdfId)

        assertTrue(again is DownloadResult.Ready)
        assertEquals(1, s.h.server.logged("fetchAttachment").size)
    }

    @Test
    fun `a file that disappeared is fetched again`() = runTest {
        val s = start()
        s.download(s.pdfId)
        storage.files.clear()

        s.download(s.pdfId)

        assertEquals(2, s.h.server.logged("fetchAttachment").size)
    }

    @Test
    fun `no connection leaves the attachment failed and says why`() = runTest {
        val s = start()
        s.h.server.failure =
            { if (it.startsWith("fetchAttachment")) MailResult.NetworkUnavailable else null }

        val result = s.download(s.pdfId)

        assertEquals(DownloadResult.Failed(SyncProblem.NETWORK), result)
        assertEquals(AttachmentState.FAILED, s.stored(s.pdfId).state)
        assertEquals(null, s.stored(s.pdfId).localPath)
    }

    @Test
    fun `an attachment gone from the server is reported as gone`() = runTest {
        val s = start()
        s.h.server.folder("INBOX").attachments.clear()

        assertEquals(DownloadResult.Gone, s.download(s.pdfId))
        assertEquals(AttachmentState.FAILED, s.stored(s.pdfId).state)
    }

    @Test
    fun `a rejected login asks for authentication`() = runTest {
        val s = start()
        s.h.server.failure =
            { if (it.startsWith("fetchAttachment")) MailResult.AuthenticationFailed else null }

        assertEquals(DownloadResult.AuthenticationRequired, s.download(s.pdfId))
    }

    @Test
    fun `a full disk is a failure and not a crash`() = runTest {
        val s = start()
        storage.failWrites = true

        assertEquals(DownloadResult.Failed(SyncProblem.UNKNOWN), s.download(s.pdfId))
        assertEquals(AttachmentState.FAILED, s.stored(s.pdfId).state)
    }

    @Test
    fun `an unknown attachment or a local only message is gone`() = runTest {
        val s = start()
        s.h.messages.upsert(listOf(s.h.messages.getById(s.messageId)!!.copy(uid = 0)))

        assertEquals(DownloadResult.Gone, s.download(9_999))
        assertEquals(DownloadResult.Gone, s.download(s.pdfId))
    }

    @Test
    fun `no account means gone`() = runTest {
        val s = start()
        s.h.db.accountDao().delete(s.h.accountId)

        assertEquals(DownloadResult.Gone, s.download(s.pdfId))
    }

    @Test
    fun `cancelling a download leaves the attachment downloadable again`() = runTest {
        val s = start()
        val gate = CompletableDeferred<Unit>()
        s.h.connector.gate = gate
        val connectsBefore = s.h.connector.connects.size

        val job = launch { s.download(s.pdfId) }
        // Room answers on a real thread: wait until the download is held at the connection.
        withContext(Dispatchers.Default) {
            while (s.h.connector.connects.size == connectsBefore) yield()
        }
        assertEquals(AttachmentState.DOWNLOADING, s.stored(s.pdfId).state)
        job.cancelAndJoin()

        assertEquals(AttachmentState.REMOTE, s.stored(s.pdfId).state)
    }

    @Test
    fun `inline images the html shows are fetched and other attachments are not`() = runTest {
        val s = start()

        val fetched = s.download.fetchInline(s.messageId)

        assertEquals(1, fetched)
        assertEquals(AttachmentState.DOWNLOADED, s.stored(s.imageId).state)
        assertEquals(AttachmentState.REMOTE, s.stored(s.pdfId).state)
    }

    @Test
    fun `an inline image the html does not show is left alone`() = runTest {
        val s = start(html = "<p>no pictures</p>")

        assertEquals(0, s.download.fetchInline(s.messageId))
        assertEquals(AttachmentState.REMOTE, s.stored(s.imageId).state)
    }

    @Test
    fun `a message without html has no inline images to fetch`() = runTest {
        val s = start(html = null)

        assertEquals(0, s.download.fetchInline(s.messageId))
    }

    @Test
    fun `a big inline image is not fetched on its own`() = runTest {
        val s = start()
        val big = s.stored(s.imageId).copy(size = DownloadAttachment.MAX_INLINE_BYTES + 1)
        s.h.db.attachmentDao().insert(listOf(big.copy(id = 0, contentId = "logo@x")))
        s.h.db.attachmentDao().setState(s.imageId, AttachmentState.DOWNLOADED, "/x")

        assertEquals(0, s.download.fetchInline(s.messageId))
    }
}
