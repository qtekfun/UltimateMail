// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingScheduler : SyncScheduler {
    val requests = mutableListOf<Long?>()

    override fun startPeriodic() = Unit

    override fun requestSync(accountId: Long?, userInitiated: Boolean) {
        requests += accountId
    }

    override fun stop() = Unit
}

class ConversationActionsTest {
    private var harness: EngineHarness? = null
    private val scheduler = RecordingScheduler()

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): Pair<EngineHarness, ConversationActions> {
        val h = EngineHarness(this)
        harness = h
        h.addAccount()
        h.db.folderDao().upsert(
            listOf(
                folder(h.accountId),
                folder(h.accountId, "Archive", FolderRole.ARCHIVE)
            )
        )
        h.messages.upsert(
            listOf(
                message(h.accountId, 1, seen = false),
                message(h.accountId, 2, seen = true, flagged = false),
                message(h.accountId, 0, seen = false)
            )
        )
        return h to ConversationActions(h.messages, h.queue, h.marker, scheduler)
    }

    private suspend fun EngineHarness.idOf(uid: Long) = messages.get(accountId, "INBOX", uid)!!.id

    @Test
    fun `marking read changes the row at once, queues a flag change and asks for a sync`() =
        runTest {
            val (h, actions) = start()

            actions.markRead(h.idOf(1))

            val row = h.messages.get(h.accountId, "INBOX", 1)!!
            assertTrue(row.seen)
            assertTrue(row.pendingSync)
            val queued = h.operations.all(h.accountId).single()
            assertEquals(OperationType.SET_FLAGS, queued.type)
            assertEquals(1L, queued.uid)
            assertEquals(FlagChange(seen = true), FlagChange.decode(queued.payload))
            assertEquals(listOf<Long?>(h.accountId), scheduler.requests)
        }

    @Test
    fun `marking read what is read already does nothing`() = runTest {
        val (h, actions) = start()

        actions.markRead(h.idOf(2))

        assertTrue(h.operations.all(h.accountId).isEmpty())
        assertTrue(scheduler.requests.isEmpty())
        assertFalse(h.messages.get(h.accountId, "INBOX", 2)!!.pendingSync)
    }

    @Test
    fun `reading and then marking unread merges into one queued change`() = runTest {
        val (h, actions) = start()

        actions.markRead(h.idOf(1))
        actions.markUnread(h.idOf(1))

        val queued = h.operations.all(h.accountId).single()
        assertEquals(FlagChange(seen = false), FlagChange.decode(queued.payload))
        assertFalse(h.messages.get(h.accountId, "INBOX", 1)!!.seen)
    }

    @Test
    fun `starring touches only the star`() = runTest {
        val (h, actions) = start()

        actions.setStarred(h.idOf(2), true)

        val row = h.messages.get(h.accountId, "INBOX", 2)!!
        assertTrue(row.flagged)
        assertTrue(row.seen)
        val queued = h.operations.all(h.accountId).single()
        assertEquals(FlagChange(flagged = true), FlagChange.decode(queued.payload))
    }

    @Test
    fun `a message that is not on the server changes locally only`() = runTest {
        val (h, actions) = start()

        actions.markRead(h.idOf(0))

        assertTrue(h.messages.get(h.accountId, "INBOX", 0)!!.seen)
        assertTrue(h.operations.all(h.accountId).isEmpty())
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `an unknown message is ignored`() = runTest {
        val (h, actions) = start()

        actions.markRead(9_999)

        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `moving queues one move per message and waits with the sync`() = runTest {
        val (h, actions) = start()

        val undo = actions.move(listOf(h.idOf(1), h.idOf(2)), "Archive")

        val queued = h.operations.all(h.accountId)
        assertEquals(listOf(1L, 2L), queued.map { it.uid })
        assertTrue(queued.all { it.type == OperationType.MOVE && it.payload == "Archive" })
        assertEquals(2, undo!!.inverse.size)
        assertTrue(scheduler.requests.isEmpty(), "the sync waits for the undo window")
    }

    @Test
    fun `undoing a move that was not sent cancels it`() = runTest {
        val (h, actions) = start()
        val undo = actions.move(listOf(h.idOf(1)), "Archive")!!

        actions.undo(undo)

        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `messages already in the target or not on the server are not moved`() = runTest {
        val (h, actions) = start()

        assertNull(actions.move(listOf(h.idOf(0)), "Archive"))
        assertNull(actions.move(listOf(h.idOf(1)), "INBOX"))
        assertNull(actions.move(emptyList(), "Archive"))
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `sync asks the scheduler for the account`() = runTest {
        val (h, actions) = start()

        actions.sync(h.accountId)

        assertEquals(listOf<Long?>(h.accountId), scheduler.requests)
    }
}
