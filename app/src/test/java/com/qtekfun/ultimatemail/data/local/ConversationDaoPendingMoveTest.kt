// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A message with a queued move or a queued removal of its folder's label is out of the list. */
class ConversationDaoPendingMoveTest {
    private val db = inMemoryDatabase()
    private var accountId = 0L

    @BeforeEach
    fun setUp() = runTest {
        accountId = db.accountDao().insert(account())
        db.folderDao().upsert(
            listOf(
                folder(accountId),
                folder(accountId, "Work/Invoices", FolderRole.OTHER)
            )
        )
    }

    @AfterEach
    fun close() = db.close()

    private suspend fun queue(
        type: OperationType,
        uid: Long,
        payload: String,
        folder: String = "INBOX",
        failed: Boolean = false
    ) {
        db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = accountId,
                type = type,
                folderPath = folder,
                uid = uid,
                payload = payload,
                createdAt = Instant.EPOCH,
                failed = failed
            )
        )
    }

    private suspend fun inbox() = db.conversationDao()
        .observeConversations(accountId, "INBOX", 50).first()

    private suspend fun uids() = inbox().map { it.latest.uid }

    @Test
    fun `a message with a queued move leaves the list`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1), message(accountId, 2)))
        queue(OperationType.MOVE, 1, "Archive")

        assertEquals(listOf(2L), uids())
    }

    @Test
    fun `it is back when the move is cancelled`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1)))
        queue(OperationType.MOVE, 1, "Archive")
        db.pendingOperationDao().all(accountId).forEach { db.pendingOperationDao().delete(it.id) }

        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `it is back when the server refused the move`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1)))
        queue(OperationType.MOVE, 1, "Archive", failed = true)

        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `removing the inbox label hides the message from the inbox`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1), message(accountId, 2)))
        queue(OperationType.REMOVE_LABEL, 2, "\\Inbox")

        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `adding the label again after removing it brings the message back`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1)))
        queue(OperationType.REMOVE_LABEL, 1, "\\Inbox")
        queue(OperationType.ADD_LABEL, 1, "\\Inbox")

        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `adding the label before removing it does not bring the message back`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1)))
        queue(OperationType.ADD_LABEL, 1, "\\Inbox")
        queue(OperationType.REMOVE_LABEL, 1, "\\Inbox")

        assertEquals(emptyList<Long>(), uids())
    }

    @Test
    fun `removing another label does not hide the message`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1)))
        queue(OperationType.REMOVE_LABEL, 1, "Work")
        queue(OperationType.ADD_LABEL, 1, "Other")

        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `removing a label hides the message only in the folder of that label`() = runTest {
        db.messageDao().upsert(
            listOf(
                message(accountId, 1, folderPath = "Work/Invoices"),
                message(accountId, 1, folderPath = "INBOX")
            )
        )
        queue(OperationType.REMOVE_LABEL, 1, "Work/Invoices", folder = "Work/Invoices")

        val invoices = db.conversationDao().observeConversations(
            accountId,
            "Work/Invoices",
            50
        ).first()
        assertEquals(emptyList<Long>(), invoices.map { it.latest.uid })
        assertEquals(listOf(1L), uids())
    }

    @Test
    fun `the inbox label removed from a message listed in another folder hides nothing there`() =
        runTest {
            db.messageDao().upsert(listOf(message(accountId, 1, folderPath = "Work/Invoices")))
            queue(OperationType.REMOVE_LABEL, 1, "\\Inbox", folder = "Work/Invoices")

            val invoices = db.conversationDao()
                .observeConversations(accountId, "Work/Invoices", 50).first()
            assertEquals(listOf(1L), invoices.map { it.latest.uid })
        }

    @Test
    fun `a conversation with one message gone shows the others and counts them`() = runTest {
        db.messageDao().upsert(
            listOf(
                message(accountId, 1, threadId = "t", sentAt = 1000, seen = false),
                message(accountId, 2, threadId = "t", sentAt = 2000, seen = true),
                message(accountId, 3, threadId = "t", sentAt = 3000, seen = true)
            )
        )
        queue(OperationType.MOVE, 3, "Archive")

        val row = inbox().single()

        assertEquals(2L, row.latest.uid)
        assertEquals(2, row.messageCount)
        assertEquals(1, row.unreadCount)
    }

    @Test
    fun `a conversation whose messages all moved is gone`() = runTest {
        db.messageDao().upsert(
            listOf(
                message(accountId, 1, threadId = "t"),
                message(accountId, 2, threadId = "t")
            )
        )
        queue(OperationType.MOVE, 1, "Archive")
        queue(OperationType.MOVE, 2, "Archive")

        assertEquals(emptyList<Long>(), uids())
    }

    @Test
    fun `the unified inbox drops them too`() = runTest {
        db.messageDao().upsert(listOf(message(accountId, 1), message(accountId, 2)))
        queue(OperationType.MOVE, 1, "Archive")

        db.conversationDao().observeUnifiedInbox(50).test {
            assertEquals(listOf(2L), awaitItem().map { it.latest.uid })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `other accounts are not affected by the same uid`() = runTest {
        val other = db.accountDao().insert(account("other@example.test"))
        db.folderDao().upsert(listOf(folder(other)))
        db.messageDao().upsert(listOf(message(accountId, 1), message(other, 1)))
        queue(OperationType.MOVE, 1, "Archive")

        val theirs = db.conversationDao().observeConversations(other, "INBOX", 50).first()
        assertEquals(listOf(1L), theirs.map { it.latest.uid })
        assertEquals(emptyList<Long>(), uids())
    }
}
