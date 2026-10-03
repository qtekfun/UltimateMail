// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A message with a move or delete waiting for the server disappears from the lists at once. */
class ConversationLeavingTest {
    private val db = inMemoryDatabase()
    private val conversations = db.conversationDao()

    @AfterEach
    fun close() = db.close()

    private suspend fun setUp(): Long {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id, "INBOX", FolderRole.INBOX)))
        db.messageDao().upsert(
            listOf(
                message(id, 1, threadId = "a", sentAt = 1_000, seen = false),
                message(id, 2, threadId = "a", sentAt = 2_000, seen = false),
                message(id, 3, threadId = "b", sentAt = 3_000)
            )
        )
        return id
    }

    private suspend fun queue(
        accountId: Long,
        uid: Long,
        type: OperationType = OperationType.MOVE,
        failed: Boolean = false
    ) = db.pendingOperationDao().enqueue(
        PendingOperationEntity(
            accountId = accountId,
            type = type,
            folderPath = "INBOX",
            uid = uid,
            payload = "Archive",
            createdAt = Instant.ofEpochMilli(1),
            failed = failed
        )
    )

    private suspend fun folderList(id: Long) =
        conversations.observeConversations(id, "INBOX", 50).first()

    @Test
    fun `without operations every conversation is listed`() = runTest {
        val id = setUp()

        assertEquals(listOf("b", "a"), folderList(id).map { it.latest.threadId })
    }

    @Test
    fun `a conversation whose messages are all moving is hidden`() = runTest {
        val id = setUp()
        queue(id, 1)
        queue(id, 2, OperationType.DELETE)

        assertEquals(listOf("b"), folderList(id).map { it.latest.threadId })
    }

    @Test
    fun `when only the newest message moves the row shows the rest of the conversation`() =
        runTest {
            val id = setUp()
            queue(id, 2)

            val row = folderList(id).single { it.latest.threadId == "a" }

            assertEquals(1L, row.latest.uid)
            assertEquals(1, row.messageCount)
            assertEquals(1, row.unreadCount)
        }

    @Test
    fun `the unified inbox hides moving conversations too`() = runTest {
        val id = setUp()
        queue(id, 3)

        assertEquals(
            listOf("a"),
            conversations.observeUnifiedInbox(50).first().map {
                it.latest.threadId
            }
        )
    }

    @Test
    fun `a move refused for good brings the conversation back`() = runTest {
        val id = setUp()
        queue(id, 3, failed = true)

        assertTrue(folderList(id).any { it.latest.threadId == "b" })
    }

    @Test
    fun `other operations do not hide anything`() = runTest {
        val id = setUp()
        queue(id, 3, OperationType.SET_FLAGS)

        assertEquals(2, folderList(id).size)
    }

    @Test
    fun `cancelling the move shows the conversation again`() = runTest {
        val id = setUp()
        val operation = queue(id, 3)
        assertEquals(1, folderList(id).size)

        db.pendingOperationDao().deleteUnstarted(operation)

        assertEquals(2, folderList(id).size)
    }
}
