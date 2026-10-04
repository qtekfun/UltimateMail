// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Big mailboxes, small offline windows, pagination, pruning and file cleanup. */
class LargeMailboxTest {
    private val opened = mutableListOf<EngineHarness>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private suspend fun TestScope.start(
        windowDays: Int?,
        mails: Int,
        hoursApart: Long = 1,
        bodies: Boolean = true
    ): EngineHarness {
        val h = EngineHarness(this)
        opened += h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.server.folder("Archive", MailFolderRole.ARCHIVE)
        val now = h.clock.now
        // Message n was sent (mails - n) hours before now: the highest UID is the newest.
        for (n in 1..mails) {
            h.mail("INBOX", "m$n", now.minus(Duration.ofHours((mails - n) * hoursApart)), bodies)
        }
        h.addAccount(account().copy(offlineWindowDays = windowDays))
        h.offlineDownloads.setEnabled(h.accountId, true)
        return h
    }

    private fun EngineHarness.mail(folder: String, tag: String, sentAt: Instant, body: Boolean) {
        val uid = server.deliver(
            folder,
            subject = "S $tag",
            messageId = "<$tag@example.test>",
            sentAt = sentAt
        )
        if (body) server.folder(folder).bodies[uid] = MessageBody("t $tag", null, emptyList())
    }

    private fun EngineHarness.actions() =
        ConversationActions(messages, queue, marker, QuietScheduler())

    @Test
    fun `a window of a week keeps only that week, fetching newest first and stopping early`() =
        runTest {
            val h = start(windowDays = 7, mails = 450)

            h.engine.sync(h.accountId)

            val cutoff = h.clock.now.minus(Duration.ofDays(7))
            val expected = (1..450).count { n ->
                !h.clock.now.minus(Duration.ofHours((450 - n).toLong())).isBefore(cutoff)
            }
            val stored = h.messages.serverUids(h.accountId, "INBOX")
            assertEquals(expected, stored.size)
            assertEquals(450L, stored.max(), "the newest is there")
            assertEquals(
                listOf("fetchHeaders INBOX 251-450", "fetchHeaders INBOX 51-250"),
                h.server.logged("fetchHeaders INBOX"),
                "stops at the first batch that is all older than the window"
            )
            assertEquals(expected, h.server.logged("fetchBody").size, "bodies only inside it")
        }

    @Test
    fun `a drop after the first page of a big mailbox converges once the link is back`() = runTest {
        val reference = start(windowDays = null, mails = 450, bodies = false)
        reference.engine.sync(reference.accountId)
        val expected = reference.roomState()

        val h = start(windowDays = null, mails = 450, bodies = false)
        val net = FlakyNetwork(h)
        // listFolders, status INBOX, page 1; page 2 is the call that is cut (the first sync
        // reads the Inbox before the other folders).
        net.dropBefore(4)
        h.engine.sync(h.accountId)
        assertEquals(200, h.messages.serverUids(h.accountId, "INBOX").size, "page 1 is kept")
        assertNull(
            h.folders.get(h.accountId, "INBOX")!!.uidNext,
            "the folder is not marked done"
        )
        net.restore()
        h.syncUntilDone()

        assertEquals(expected, h.roomState())
        assertEquals(450, h.messages.serverUids(h.accountId, "INBOX").size)
    }

    @Test
    fun `pruning keeps old messages that still have a change waiting for the server`() = runTest {
        val h = start(windowDays = 7, mails = 3, hoursApart = 24 * 3)
        h.engine.sync(h.accountId)
        assertEquals(3, h.messages.serverUids(h.accountId, "INBOX").size)
        val read = h.messages.get(h.accountId, "INBOX", 1)!!.id
        val moved = h.messages.get(h.accountId, "INBOX", 2)!!.id
        val idle = h.messages.get(h.accountId, "INBOX", 3)!!.id
        h.actions().markRead(read)
        h.actions().move(listOf(moved), "Archive")
        // Three weeks go by with the server refusing, so nothing can be sent.
        h.clock.now = h.clock.now.plus(Duration.ofDays(21))
        h.server.failure = { name ->
            MailResult.ServerRejected(RejectionKind.NO, permanent = false)
                .takeIf { name.startsWith("setFlags") || name.startsWith("move") }
        }

        h.engine.sync(h.accountId)

        assertNotNull(h.messages.getById(read), "the message with a flag change is not pruned")
        assertNotNull(h.messages.getById(moved), "nor the one with a move waiting")
        assertNull(h.messages.getById(idle), "an idle old message is pruned")
        // The server recovers: the changes go out, and only then may the rows age out.
        h.server.failure = { null }
        h.syncUntilDone()
        assertTrue(h.server.copy("INBOX", "m1")!!.flags.seen)
        assertEquals(1, h.server.copies("m2"))
        assertNotNull(h.server.copy("Archive", "m2"))
        h.syncUntilDone()
        assertTrue(h.messages.serverUids(h.accountId, "INBOX").isEmpty())
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `cleanup removes the files of pruned messages and never those of rows that still exist`() =
        runTest {
            val h = start(windowDays = 7, mails = 3, hoursApart = 24 * 3)
            h.engine.sync(h.accountId)
            // m1 is 6 days old, m2 3 days, m3 now. Every message has a downloaded attachment.
            val ids = (1L..3L).map { h.messages.get(h.accountId, "INBOX", it)!!.id }
            val files = ids.map { messageId ->
                val attachment = AttachmentEntity(
                    messageId = messageId,
                    partId = "2",
                    fileName = "f$messageId.bin",
                    mimeType = "application/octet-stream",
                    size = 3
                )
                h.db.attachmentDao().insert(listOf(attachment))
                val saved = h.db.attachmentDao().listFor(messageId).single()
                h.storage.write(h.accountId, saved, byteArrayOf(1, 2, 3))
            }
            // m1 also has a change waiting, so it survives the pruning that takes m2.
            h.actions().markRead(ids[0])
            h.clock.now = h.clock.now.plus(Duration.ofDays(5))
            h.server.failure = { name ->
                MailResult.NetworkUnavailable.takeIf { name.startsWith("setFlags") }
            }

            h.engine.sync(h.accountId)

            assertNotNull(h.messages.getById(ids[0]))
            assertNull(h.messages.getById(ids[1]), "m2 is now older than the window")
            assertNotNull(h.messages.getById(ids[2]))
            assertTrue(h.storage.exists(files[0]), "file of a message with a pending change")
            assertTrue(!h.storage.exists(files[1]), "file of the pruned message is gone")
            assertTrue(h.storage.exists(files[2]), "file of a message inside the window")
            assertEquals(2, h.storage.files.size)
        }
}
