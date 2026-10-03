// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.OutgoingAttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DraftDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.draftDao()
    private var accountId = 0L

    @BeforeEach
    fun setUp() = runTest { accountId = db.accountDao().insert(account()) }

    @AfterEach
    fun close() = db.close()

    private fun draft(
        key: String,
        updatedAt: Long = 1,
        state: DraftState = DraftState.EDITING,
        account: Long = accountId
    ) = DraftEntity(
        key = key,
        accountId = account,
        kind = DraftKind.NEW,
        state = state,
        toAddresses = listOf("Bob <bob@example.test>", "cy@example.test"),
        referenceIds = listOf("<a@x>", "<b@x>"),
        createdAt = Instant.ofEpochMilli(0),
        updatedAt = Instant.ofEpochMilli(updatedAt)
    )

    private fun attachment(draftId: Long, size: Long = 10) = OutgoingAttachmentEntity(
        draftId = draftId,
        displayName = "a.pdf",
        mimeType = "application/pdf",
        size = size,
        filePath = "/outbox/$draftId/1-a.pdf"
    )

    @Test
    fun `a draft keeps its recipients and references as stored`() = runTest {
        val id = dao.insert(draft("k1"))

        val stored = dao.get(id)!!

        assertEquals(listOf("Bob <bob@example.test>", "cy@example.test"), stored.toAddresses)
        assertEquals(listOf("<a@x>", "<b@x>"), stored.referenceIds)
        assertEquals(DraftState.EDITING, stored.state)
        assertTrue(stored.dirty)
        assertEquals(0, stored.revision)
        assertEquals(stored, dao.getByKey("k1"))
    }

    @Test
    fun `two drafts cannot share a key`() = runTest {
        dao.insert(draft("k1"))

        val duplicate = runCatching { dao.insert(draft("k1")) }

        assertTrue(duplicate.isFailure)
    }

    @Test
    fun `drafts of a state come newest first and only for that account and state`() = runTest {
        val other = db.accountDao().insert(account("other@example.test"))
        dao.insert(draft("old", updatedAt = 1))
        dao.insert(draft("new", updatedAt = 5))
        dao.insert(draft("sending", updatedAt = 9, state = DraftState.OUTBOX))
        dao.insert(draft("elsewhere", updatedAt = 7, account = other))

        dao.observeByState(accountId, DraftState.EDITING).test {
            assertEquals(listOf("new", "old"), awaitItem().map { it.key })
            cancelAndIgnoreRemainingEvents()
        }
        dao.observeAllByState(DraftState.EDITING).test {
            assertEquals(listOf("elsewhere", "new", "old"), awaitItem().map { it.key })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the counts follow the drafts`() = runTest {
        dao.observeCount(accountId, DraftState.EDITING).test {
            assertEquals(0, awaitItem())
            val id = dao.insert(draft("k1"))
            assertEquals(1, awaitItem())
            dao.update(dao.get(id)!!.copy(state = DraftState.OUTBOX))
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        dao.observeCountAll(DraftState.OUTBOX).test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an upload clears the dirty mark only when the draft did not change meanwhile`() = runTest {
        val id = dao.insert(draft("k1").copy(revision = 3))

        dao.markUploaded(id, "<copy@x>", revision = 2)
        assertTrue(dao.get(id)!!.dirty)
        assertEquals("<copy@x>", dao.get(id)!!.serverMessageId)

        dao.markUploaded(id, "<copy2@x>", revision = 3)
        assertFalse(dao.get(id)!!.dirty)
        assertEquals("<copy2@x>", dao.get(id)!!.serverMessageId)
    }

    @Test
    fun `SMTP acceptance is recorded once and the first time stays`() = runTest {
        val id = dao.insert(draft("k1"))

        dao.markSmtpAccepted(id, Instant.ofEpochMilli(100))
        dao.markSmtpAccepted(id, Instant.ofEpochMilli(200))

        assertEquals(Instant.ofEpochMilli(100), dao.get(id)!!.smtpAcceptedAt)
    }

    @Test
    fun `a draft can be given a new key`() = runTest {
        val id = dao.insert(draft("k1"))

        dao.rekey(id, "k2")

        assertNull(dao.getByKey("k1"))
        assertEquals(id, dao.getByKey("k2")!!.id)
    }

    @Test
    fun `attachments are listed in order, summed and removed one by one`() = runTest {
        val id = dao.insert(draft("k1"))
        val first = dao.insertAttachment(attachment(id, 10))
        dao.insertAttachment(attachment(id, 32))

        assertEquals(42L, dao.attachmentBytes(id))
        assertEquals(2, dao.attachments(id).size)
        assertNotNull(dao.attachment(first))

        dao.deleteAttachment(first)

        assertNull(dao.attachment(first))
        assertEquals(32L, dao.attachmentBytes(id))
        dao.observeAttachments(id).test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a draft without attachments weighs nothing`() = runTest {
        assertEquals(0L, dao.attachmentBytes(dao.insert(draft("k1"))))
    }

    @Test
    fun `deleting a draft deletes its attachments`() = runTest {
        val id = dao.insert(draft("k1"))
        dao.insertAttachment(attachment(id))

        dao.delete(id)

        assertTrue(dao.attachments(id).isEmpty())
    }

    @Test
    fun `deleting an account deletes its drafts and their attachments`() = runTest {
        val other = db.accountDao().insert(account("other@example.test"))
        val doomed = dao.insert(draft("k1"))
        val kept = dao.insert(draft("k2", account = other))
        dao.insertAttachment(attachment(doomed))
        assertEquals(listOf(doomed), dao.idsOf(accountId))

        db.accountDao().delete(accountId)

        assertNull(dao.get(doomed))
        assertTrue(dao.attachments(doomed).isEmpty())
        assertNotNull(dao.get(kept))
    }

    @Test
    fun `an unknown draft is not found`() = runTest {
        assertNull(dao.get(99))
        dao.observe(99).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the queue queries that belong to drafts ---

    private fun operation(type: OperationType, draftId: Long, folder: String = "") =
        PendingOperationEntity(
            accountId = accountId,
            type = type,
            folderPath = folder,
            uid = draftId,
            payload = "p",
            createdAt = Instant.ofEpochMilli(1)
        )

    @Test
    fun `the operations of one draft are found by type without touching server messages`() =
        runTest {
            val queue = db.pendingOperationDao()
            queue.enqueue(operation(OperationType.SAVE_DRAFT, 5))
            queue.enqueue(operation(OperationType.SEND, 5))
            queue.enqueue(operation(OperationType.SAVE_DRAFT, 6))
            // A move of the message with uid 5 in a real folder is not a draft operation.
            queue.enqueue(operation(OperationType.MOVE, 5, folder = "INBOX"))

            assertEquals(1, queue.forDraft(accountId, 5, OperationType.SEND).size)

            queue.deleteForDraft(accountId, 5, OperationType.SAVE_DRAFT)

            assertTrue(queue.forDraft(accountId, 5, OperationType.SAVE_DRAFT).isEmpty())
            assertEquals(1, queue.forDraft(accountId, 6, OperationType.SAVE_DRAFT).size)
            assertEquals(3, queue.all(accountId).size)
        }

    @Test
    fun `the send operations are observed per account and across accounts`() = runTest {
        val queue = db.pendingOperationDao()
        val other = db.accountDao().insert(account("other@example.test"))
        queue.enqueue(operation(OperationType.SEND, 1))
        queue.enqueue(operation(OperationType.SAVE_DRAFT, 2))
        queue.enqueue(operation(OperationType.SEND, 3).copy(accountId = other))

        queue.observeSends(accountId).test {
            assertEquals(listOf(1L), awaitItem().map { it.uid })
            cancelAndIgnoreRemainingEvents()
        }
        queue.observeAllSends().test {
            assertEquals(listOf(1L, 3L), awaitItem().map { it.uid })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- the message queries that belong to the composer ---

    @Test
    fun `server drafts are the messages of Drafts folders with a server uid`() = runTest {
        val other = db.accountDao().insert(account("other@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(accountId, "INBOX"),
                folder(accountId, "Drafts", FolderRole.DRAFTS),
                folder(other, "Drafts", FolderRole.DRAFTS)
            )
        )
        db.messageDao().upsert(
            listOf(
                message(accountId, 1, "Drafts", sentAt = 10),
                message(accountId, 2, "Drafts", sentAt = 20),
                message(accountId, 0, "Drafts", sentAt = 30),
                message(accountId, 3, "INBOX"),
                message(other, 1, "Drafts", sentAt = 5)
            )
        )

        db.messageDao().observeServerDrafts(accountId).test {
            assertEquals(listOf(2L, 1L), awaitItem().map { it.uid })
            cancelAndIgnoreRemainingEvents()
        }
        db.messageDao().observeAllServerDrafts().test {
            assertEquals(3, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `address samples skip drafts, junk and trash, and flag what the user sent`() = runTest {
        db.folderDao().upsert(
            listOf(
                folder(accountId, "INBOX"),
                folder(accountId, "Sent", FolderRole.SENT),
                folder(accountId, "Drafts", FolderRole.DRAFTS),
                folder(accountId, "Junk", FolderRole.JUNK),
                folder(accountId, "Trash", FolderRole.TRASH)
            )
        )
        db.messageDao().upsert(
            listOf(
                message(accountId, 1, "INBOX", sentAt = 100),
                message(accountId, 2, "Sent", sentAt = 200),
                message(accountId, 3, "Drafts", sentAt = 300),
                message(accountId, 4, "Junk", sentAt = 400),
                message(accountId, 5, "Trash", sentAt = 500)
            )
        )

        val samples = db.messageDao().addressSamples(accountId, 10)

        assertEquals(listOf(true, false), samples.map { it.fromUser })
        assertEquals(2, samples.size)
        assertEquals(1, db.messageDao().addressSamples(accountId, 1).size)
        assertEquals(Instant.ofEpochMilli(200), samples.first().sentAt)
    }
}
