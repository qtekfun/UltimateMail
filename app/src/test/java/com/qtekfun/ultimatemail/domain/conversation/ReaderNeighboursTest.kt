// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReaderNeighboursTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var neighbours: ReaderNeighbours
    private var first = 0L
    private var second = 0L

    @BeforeEach
    fun setUp() = runTest {
        db = inMemoryDatabase()
        neighbours = ReaderNeighbours(db)
        first = db.accountDao().insert(account("ana@example.test"))
        second = db.accountDao().insert(account("bea@example.test"))
        listOf(first, second).forEach {
            db.folderDao().upsert(
                listOf(folder(it), folder(it, "Archive", FolderRole.ARCHIVE))
            )
        }
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun ref(account: Long, thread: String) = ConversationRef(account, "INBOX", thread)

    private suspend fun around(current: ConversationRef, list: ReaderList?): Neighbours =
        neighbours.observe(current, list).first()

    private fun inbox(unreadOnly: Boolean = false) =
        ReaderList(InboxScope.Folder(first, "INBOX"), unreadOnly)

    private suspend fun threeInInbox() {
        db.messageDao().upsert(
            listOf(
                message(first, 1, threadId = "old", sentAt = 1_000, seen = true),
                message(first, 2, threadId = "mid", sentAt = 2_000, seen = false),
                message(first, 3, threadId = "new", sentAt = 3_000, seen = true)
            )
        )
    }

    @Test
    fun `the middle conversation has a newer and an older neighbour`() = runTest {
        threeInInbox()

        val found = around(ref(first, "mid"), inbox())

        assertEquals(ref(first, "new"), found.previous)
        assertEquals(ref(first, "old"), found.next)
    }

    @Test
    fun `the ends of the list have no neighbour on that side`() = runTest {
        threeInInbox()

        assertNull(around(ref(first, "new"), inbox()).previous)
        assertEquals(ref(first, "mid"), around(ref(first, "new"), inbox()).next)
        assertNull(around(ref(first, "old"), inbox()).next)
    }

    @Test
    fun `a conversation stands where its newest message is`() = runTest {
        threeInInbox()
        db.messageDao().upsert(listOf(message(first, 4, threadId = "old", sentAt = 5_000)))

        val found = around(ref(first, "mid"), inbox())

        assertEquals(ref(first, "new"), found.previous)
        assertNull(found.next)
        assertEquals(ref(first, "new"), around(ref(first, "old"), inbox()).next)
        assertNull(around(ref(first, "old"), inbox()).previous)
    }

    @Test
    fun `messages sent at the same moment are ordered by id`() = runTest {
        db.messageDao().upsert(
            listOf(
                message(first, 1, threadId = "a", sentAt = 1_000),
                message(first, 2, threadId = "b", sentAt = 1_000)
            )
        )

        val found = around(ref(first, "a"), inbox())

        assertEquals(ref(first, "b"), found.previous)
        assertNull(found.next)
    }

    @Test
    fun `only the unread conversations count when the list was filtered`() = runTest {
        threeInInbox()

        val found = around(ref(first, "new"), inbox(unreadOnly = true))

        assertNull(found.previous)
        assertEquals(ref(first, "mid"), found.next)
        assertNull(around(ref(first, "mid"), inbox(unreadOnly = true)).next)
    }

    @Test
    fun `other folders and accounts are not part of a folder list`() = runTest {
        threeInInbox()
        db.messageDao().upsert(
            listOf(
                message(first, 10, folderPath = "Archive", threadId = "arch", sentAt = 2_500),
                message(second, 11, threadId = "other", sentAt = 2_600)
            )
        )

        val found = around(ref(first, "mid"), inbox())

        assertEquals(ref(first, "new"), found.previous)
    }

    @Test
    fun `the unified list walks the inboxes of every account`() = runTest {
        threeInInbox()
        db.messageDao().upsert(
            listOf(
                message(second, 11, threadId = "other", sentAt = 2_600),
                message(first, 12, folderPath = "Archive", threadId = "arch", sentAt = 2_700)
            )
        )
        val unified = ReaderList(InboxScope.Unified)

        val found = around(ref(first, "mid"), unified)

        assertEquals(ref(second, "other"), found.previous)
        assertEquals(ref(first, "old"), found.next)
        assertEquals(ref(first, "mid"), around(ref(second, "other"), unified).next)
    }

    @Test
    fun `unified and unread together`() = runTest {
        threeInInbox()
        db.messageDao().upsert(
            listOf(message(second, 11, threadId = "other", sentAt = 2_600, seen = false))
        )

        val found = around(ref(first, "mid"), ReaderList(InboxScope.Unified, unreadOnly = true))

        assertEquals(ref(second, "other"), found.previous)
        assertNull(found.next)
    }

    @Test
    fun `a conversation that left the list through a queued move is skipped`() = runTest {
        threeInInbox()
        db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = first,
                type = OperationType.MOVE,
                folderPath = "INBOX",
                uid = 3,
                payload = "Archive",
                createdAt = Instant.EPOCH
            )
        )

        val found = around(ref(first, "mid"), inbox())

        assertNull(found.previous)
        assertEquals(ref(first, "old"), found.next)
    }

    @Test
    fun `a conversation that is not visible has no neighbours`() = runTest {
        threeInInbox()

        assertEquals(Neighbours(), around(ref(first, "gone"), inbox()))
    }

    @Test
    fun `without a list there is nothing to walk`() = runTest {
        threeInInbox()

        assertEquals(Neighbours(), around(ref(first, "mid"), null))
    }

    @Test
    fun `a reader list survives its saved text form`() {
        val lists = listOf(
            ReaderList(InboxScope.Unified),
            ReaderList(InboxScope.Folder(3, "Work/a:b"), unreadOnly = true)
        )

        lists.forEach { assertEquals(it, ReaderList.fromKey(it.key)) }
        assertNull(ReaderList.fromKey(null))
        assertNull(ReaderList.fromKey("x:unified"))
        assertNull(ReaderList.fromKey("a:"))
        assertNull(ReaderList.fromKey("a:nonsense"))
    }
}
