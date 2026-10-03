// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import java.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentFileCleanerTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): EngineHarness {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.addAccount()
        h.offlineDownloads.setEnabled(h.accountId, true)
        return h
    }

    private fun EngineHarness.deliverWithAttachment(daysAgo: Long = 5): Long {
        val uid = server.deliver("INBOX", sentAt = clock.now.minus(Duration.ofDays(daysAgo)))
        server.folder("INBOX").bodies[uid] = MessageBody(
            "t",
            null,
            listOf(AttachmentInfo("2", "a.pdf", "application/pdf", 3, null, false))
        )
        server.folder("INBOX").attachments[uid to "2"] = byteArrayOf(1, 2, 3)
        return uid
    }

    private suspend fun EngineHarness.download(uid: Long) {
        val id = messages.get(accountId, "INBOX", uid)!!.id
        val attachment = db.attachmentDao().listFor(id).single()
        DownloadAttachment(db.attachmentDao(), messages, sessions, storage)(attachment.id)
    }

    @Test
    fun `only files without an attachment row are removed`() = runTest {
        val h = start()
        val uid = h.deliverWithAttachment()
        h.engine.sync(h.accountId)
        h.download(uid)
        val kept = h.storage.files.keys.single()
        val otherAccount = "/files/${h.accountId + 1}/99/778"
        h.storage.files["/files/${h.accountId}/99/777"] = byteArrayOf(9)
        h.storage.files[otherAccount] = byteArrayOf(9)

        val removed = AttachmentFileCleaner(h.db.attachmentDao(), h.storage).clean(h.accountId)

        assertEquals(1, removed)
        assertEquals(setOf(kept, otherAccount), h.storage.files.keys)
    }

    @Test
    fun `nothing stored means nothing to do`() = runTest {
        val h = start()

        assertEquals(0, AttachmentFileCleaner(h.db.attachmentDao(), h.storage).clean(h.accountId))
    }

    @Test
    fun `a message expunged on the server loses its downloaded files at the next sync`() = runTest {
        val h = start()
        val uid = h.deliverWithAttachment()
        h.engine.sync(h.accountId)
        h.download(uid)
        assertEquals(1, h.storage.files.size)
        h.server.expunge("INBOX", uid)

        h.engine.sync(h.accountId)

        assertTrue(h.storage.files.isEmpty())
        assertTrue(h.db.attachmentDao().idsOfAccount(h.accountId).isEmpty())
    }

    @Test
    fun `messages that leave the offline window lose their files and bodies`() = runTest {
        val h = start()
        val uid = h.deliverWithAttachment(daysAgo = 80)
        h.engine.sync(h.accountId)
        h.download(uid)
        h.clock.now = h.clock.now.plus(Duration.ofDays(20))

        h.engine.sync(h.accountId)

        assertTrue(h.storage.files.isEmpty())
        assertNull(h.messages.get(h.accountId, "INBOX", uid))
    }

    @Test
    fun `files of attachments that still exist survive a sync`() = runTest {
        val h = start()
        val uid = h.deliverWithAttachment()
        h.engine.sync(h.accountId)
        h.download(uid)

        h.engine.sync(h.accountId)

        val row = h.db.attachmentDao().listFor(h.messages.get(h.accountId, "INBOX", uid)!!.id)
            .single()
        assertTrue(h.storage.exists(row.localPath!!))
    }
}
