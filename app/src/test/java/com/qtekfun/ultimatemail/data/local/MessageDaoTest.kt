// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.messageDao()
    private val conversations = db.conversationDao()
    private var accountId = 0L

    @BeforeEach
    fun setUp() = runTest {
        accountId = db.accountDao().insert(account())
        db.folderDao().upsert(
            listOf(folder(accountId), folder(accountId, "Work", FolderRole.OTHER))
        )
    }

    @AfterEach
    fun close() = db.close()

    @Test
    fun `upserting the same server identity replaces the message`() = runTest {
        dao.upsert(listOf(message(accountId, 1, subject = "old")))
        dao.upsert(listOf(message(accountId, 1, subject = "new")))

        assertEquals("new", dao.get(accountId, "INBOX", 1)!!.subject)
    }

    @Test
    fun `conversations show the newest message with its counters`() = runTest {
        dao.upsert(
            listOf(
                message(accountId, 1, threadId = "a", sentAt = 1000, seen = true),
                message(accountId, 2, threadId = "a", sentAt = 3000),
                message(accountId, 3, threadId = "b", sentAt = 2000, seen = true)
            )
        )

        conversations.observeConversations(accountId, "INBOX", 50).test {
            val rows = awaitItem()
            assertEquals(listOf(2L, 3L), rows.map { it.latest.uid })
            assertEquals(2, rows[0].messageCount)
            assertEquals(1, rows[0].unreadCount)
            assertEquals(0, rows[1].unreadCount)
        }
    }

    @Test
    fun `conversations honor the limit`() = runTest {
        dao.upsert((1L..5L).map { message(accountId, it) })

        conversations.observeConversations(accountId, "INBOX", 2).test {
            assertEquals(2, awaitItem().size)
        }
    }

    @Test
    fun `a thread lists its messages oldest first`() = runTest {
        dao.upsert(
            listOf(
                message(accountId, 2, threadId = "a", sentAt = 5),
                message(accountId, 1, threadId = "a", sentAt = 1)
            )
        )

        dao.observeThread(accountId, "INBOX", "a").test {
            assertEquals(listOf(1L, 2L), awaitItem().map { it.uid })
        }
    }

    @Test
    fun `the unified inbox merges the INBOX of every account and skips other folders`() = runTest {
        val other = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(other)))
        dao.upsert(
            listOf(
                message(accountId, 1, sentAt = 1000),
                message(other, 1, sentAt = 2000),
                message(accountId, 2, folderPath = "Work", sentAt = 9000)
            )
        )

        conversations.observeUnifiedInbox(50).test {
            assertEquals(
                listOf(other to 1L, accountId to 1L),
                awaitItem().map {
                    it.latest.accountId to
                        it.latest.uid
                }
            )
        }
    }

    @Test
    fun `search finds subject, sender and cached bodies`() = runTest {
        dao.upsert(
            listOf(
                message(accountId, 1, subject = "Invoice March"),
                message(accountId, 2, bodyText = "the quarterly budget is attached"),
                message(accountId, 3, subject = "Lunch")
            )
        )

        assertEquals(listOf(1L), conversations.search("invoice", null, 10).map { it.uid })
        assertEquals(listOf(2L), conversations.search("budget", null, 10).map { it.uid })
        assertEquals(3, conversations.search("bob", null, 10).size)
    }

    @Test
    fun `search can be limited to one account and follows updates and deletes`() = runTest {
        val other = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(other)))
        dao.upsert(
            listOf(message(accountId, 1, subject = "shared"), message(other, 1, subject = "shared"))
        )

        assertEquals(listOf(other), conversations.search("shared", other, 10).map { it.accountId })

        dao.setBody(accountId, "INBOX", 1, "now with zebra", null)
        assertEquals(1, conversations.search("zebra", null, 10).size)
        dao.delete(accountId, "INBOX", 1)
        assertEquals(0, conversations.search("zebra", null, 10).size)
    }

    @Test
    fun `flags and the pending indicator are stored`() = runTest {
        dao.upsert(listOf(message(accountId, 1)))

        val id = dao.get(accountId, "INBOX", 1)!!.id
        dao.setFlags(id, seen = true, flagged = true, answered = false, pendingSync = true)

        val stored = dao.get(accountId, "INBOX", 1)!!
        assertTrue(stored.seen && stored.flagged && stored.pendingSync)
        assertFalse(stored.answered)
    }

    @Test
    fun `bodies are null until fetched`() = runTest {
        dao.upsert(listOf(message(accountId, 1)))
        assertNull(dao.get(accountId, "INBOX", 1)!!.bodyHtml)

        dao.setBody(accountId, "INBOX", 1, "text", "<p>text</p>")

        assertEquals("<p>text</p>", dao.get(accountId, "INBOX", 1)!!.bodyHtml)
        dao.observe(dao.get(accountId, "INBOX", 1)!!.id).test {
            assertEquals("text", awaitItem()!!.bodyText)
        }
    }

    @Test
    fun `old headers are dropped except those with pending changes`() = runTest {
        dao.upsert(
            listOf(
                message(accountId, 1, sentAt = 100),
                message(accountId, 2, sentAt = 100),
                message(accountId, 3, sentAt = 900)
            )
        )
        val pendingId = dao.get(accountId, "INBOX", 2)!!.id
        dao.setFlags(pendingId, seen = true, flagged = false, answered = false, pendingSync = true)

        dao.deleteOlderThan(accountId, cutoffMillis = 500)

        assertNull(dao.get(accountId, "INBOX", 1))
        assertEquals(2L, dao.get(accountId, "INBOX", 2)!!.uid)
        assertEquals(3L, dao.get(accountId, "INBOX", 3)!!.uid)
    }

    @Test
    fun `pruning by age spares local-only drafts`() = runTest {
        dao.upsert(listOf(message(accountId, 0, sentAt = 1), message(accountId, 5, sentAt = 1)))

        dao.deleteOlderThan(accountId, cutoffMillis = 500)

        assertEquals(0L, dao.get(accountId, "INBOX", 0)!!.uid)
        assertNull(dao.get(accountId, "INBOX", 5))
    }

    @Test
    fun `attachments follow their message and track their state`() = runTest {
        dao.upsert(listOf(message(accountId, 1)))
        val messageId = dao.get(accountId, "INBOX", 1)!!.id
        val attachments = db.attachmentDao()
        attachments.insert(
            listOf(
                AttachmentEntity(
                    messageId = messageId,
                    partId = "2",
                    fileName = "a.pdf",
                    mimeType = "application/pdf",
                    size = 10
                )
            )
        )
        val id = attachments.observe(messageId).first().single().id

        attachments.setState(id, AttachmentState.DOWNLOADED, "/files/a.pdf")

        attachments.observe(messageId).test {
            assertEquals(AttachmentState.DOWNLOADED, awaitItem().single().state)
        }
        dao.delete(accountId, "INBOX", 1)
        attachments.observe(messageId).test { assertTrue(awaitItem().isEmpty()) }
    }
}
