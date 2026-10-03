// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.conversation.RecordingScheduler
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationBulkActionsTest {
    private var harness: EngineHarness? = null
    private val scheduler = RecordingScheduler()

    @AfterEach
    fun close() {
        harness?.close()
    }

    private class Fixture(
        val h: EngineHarness,
        val bulk: ConversationBulkActions,
        val targets: RowTargets
    ) {
        /** The row of a conversation, as the list gives it. */
        suspend fun item(
            threadId: String,
            folderPath: String = "INBOX",
            unreadCount: Int = 0,
            flagged: Boolean = false
        ): ConversationItem {
            val newest = h.messages.thread(h.accountId, folderPath, threadId).last()
            return rowItem(
                accountId = h.accountId,
                folderPath = folderPath,
                threadId = threadId,
                unreadCount = unreadCount,
                flagged = flagged,
                latestMessageId = newest.id
            )
        }

        suspend fun queued() = h.operations.all(h.accountId)
            .map { NewOperation(it.accountId, it.type, it.folderPath, it.uid, it.payload) }
    }

    /**
     * Thread "a" (uid 1 and 2, both unread), thread "b" (uid 3, read) and thread "c" in Archive
     * (uid 4), on an account with INBOX, Archive and Trash.
     */
    private suspend fun TestScope.start(withArchive: Boolean = true): Fixture {
        val h = EngineHarness(this)
        harness = h
        h.addAccount()
        val folders = buildList {
            add(folder(h.accountId))
            if (withArchive) add(folder(h.accountId, "Archive", FolderRole.ARCHIVE))
            add(folder(h.accountId, "Trash", FolderRole.TRASH))
        }
        h.db.folderDao().upsert(folders)
        h.messages.upsert(
            buildList {
                add(message(h.accountId, 1, threadId = "a", seen = false, sentAt = 1))
                add(message(h.accountId, 2, threadId = "a", seen = false, sentAt = 2))
                add(message(h.accountId, 3, threadId = "b", seen = true))
                if (withArchive) {
                    add(
                        message(h.accountId, 4, folderPath = "Archive", threadId = "c", seen = true)
                    )
                }
            }
        )
        val actions = ConversationActions(h.messages, h.queue, h.marker, scheduler)
        return Fixture(
            h,
            ConversationBulkActions(h.messages, actions),
            RowTargets(mapOf(h.accountId to folders))
        )
    }

    private fun move(accountId: Long, uid: Long, from: String, to: String) =
        NewOperation(accountId, OperationType.MOVE, from, uid, to)

    @Test
    fun `archiving queues a move to Archive for every message of the conversation`() = runTest {
        val f = start()

        val result = f.bulk.apply(RowChange.ARCHIVE, listOf(f.item("a")), f.targets)

        assertEquals(1, result.applied)
        assertEquals(
            listOf(
                move(f.h.accountId, 1, "INBOX", "Archive"),
                move(f.h.accountId, 2, "INBOX", "Archive")
            ),
            f.queued()
        )
        assertEquals(setOf(f.h.accountId), result.undo!!.accountIds)
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `deleting moves to Trash, never a permanent delete`() = runTest {
        val f = start()

        f.bulk.apply(RowChange.DELETE, listOf(f.item("b")), f.targets)

        assertEquals(listOf(move(f.h.accountId, 3, "INBOX", "Trash")), f.queued())
    }

    @Test
    fun `undoing an archive cancels the move and asks for a sync`() = runTest {
        val f = start()
        val result = f.bulk.apply(RowChange.ARCHIVE, listOf(f.item("a"), f.item("b")), f.targets)
        assertEquals(2, result.applied)
        assertEquals(3, f.queued().size)

        f.bulk.undo(result.undo!!)

        assertTrue(f.queued().isEmpty())
        assertEquals(listOf<Long?>(f.h.accountId), scheduler.requests)
    }

    @Test
    fun `what does not apply is skipped and a batch with nothing to do has no undo`() = runTest {
        val f = start()

        val result = f.bulk.apply(
            RowChange.ARCHIVE,
            listOf(f.item("c", folderPath = "Archive")),
            f.targets
        )

        assertEquals(0, result.applied)
        assertNull(result.undo)
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun `an account without an Archive folder queues nothing`() = runTest {
        val f = start(withArchive = false)

        val result = f.bulk.apply(RowChange.ARCHIVE, listOf(f.item("a")), f.targets)

        assertEquals(0, result.applied)
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun `a batch counts the conversations it changed, skipping those it cannot move`() = runTest {
        val f = start()
        val items = listOf(f.item("a"), f.item("c", folderPath = "Archive"), f.item("b"))

        val result = f.bulk.apply(RowChange.ARCHIVE, items, f.targets)

        assertEquals(2, result.applied)
        assertEquals(listOf(1L, 2L, 3L), f.queued().map { it.uid })
    }

    @Test
    fun `marking read changes the unread messages only, without syncing yet`() = runTest {
        val f = start()

        val result = f.bulk.apply(
            RowChange.MARK_READ,
            listOf(f.item("a", unreadCount = 2)),
            f.targets
        )

        assertEquals(1, result.applied)
        assertEquals(
            listOf(1L, 2L).map {
                NewOperation(
                    f.h.accountId,
                    OperationType.SET_FLAGS,
                    "INBOX",
                    it,
                    FlagChange(seen = true).encode()
                )
            },
            f.queued()
        )
        assertTrue(f.h.messages.get(f.h.accountId, "INBOX", 1)!!.seen)
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `marking read leaves a conversation that is all read alone`() = runTest {
        val f = start()

        val result = f.bulk.apply(RowChange.MARK_READ, listOf(f.item("b")), f.targets)

        assertEquals(0, result.applied)
        assertNull(result.undo)
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun `undoing mark read puts the flags back`() = runTest {
        val f = start()
        val result = f.bulk.apply(
            RowChange.MARK_READ,
            listOf(f.item("a", unreadCount = 2)),
            f.targets
        )

        f.bulk.undo(result.undo!!)

        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 1)!!.seen)
        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 2)!!.seen)
        assertEquals(
            listOf(FlagChange(seen = false), FlagChange(seen = false)),
            f.h.operations.all(f.h.accountId).map { FlagChange.decode(it.payload) }
        )
        assertEquals(listOf<Long?>(f.h.accountId), scheduler.requests)
    }

    @Test
    fun `marking unread only touches the newest message, as the reading screen does`() = runTest {
        val f = start()

        f.bulk.apply(RowChange.MARK_UNREAD, listOf(f.item("b")), f.targets)

        assertEquals(
            listOf(
                NewOperation(
                    f.h.accountId,
                    OperationType.SET_FLAGS,
                    "INBOX",
                    3,
                    FlagChange(seen = false).encode()
                )
            ),
            f.queued()
        )
    }

    @Test
    fun `starring and unstarring act on the newest message`() = runTest {
        val f = start()

        val starred = f.bulk.apply(RowChange.STAR, listOf(f.item("a")), f.targets)

        assertEquals(1, starred.applied)
        assertTrue(f.h.messages.get(f.h.accountId, "INBOX", 2)!!.flagged)
        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 1)!!.flagged)
        assertEquals(
            listOf(
                NewOperation(
                    f.h.accountId,
                    OperationType.SET_FLAGS,
                    "INBOX",
                    2,
                    FlagChange(flagged = true).encode()
                )
            ),
            f.queued()
        )

        f.bulk.apply(RowChange.UNSTAR, listOf(f.item("a", flagged = true)), f.targets)
        assertFalse(f.h.messages.get(f.h.accountId, "INBOX", 2)!!.flagged)
    }

    @Test
    fun `the messages of the conversations are handed to the folder picker`() = runTest {
        val f = start()

        val handles = f.bulk.messagesOf(listOf(f.item("a"), f.item("b")))

        assertEquals(listOf(1L, 2L, 3L), handles.map { it.uid })
        assertTrue(handles.all { it.accountId == f.h.accountId && it.folderPath == "INBOX" })
    }
}
