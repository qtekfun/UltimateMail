// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC section 5: the server and the user change the same message while apart. */
class ConcurrentChangesTest {
    private val opened = mutableListOf<EngineHarness>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private suspend fun TestScope.start(): EngineHarness {
        val h = EngineHarness(this)
        opened += h
        h.server.populate()
        h.addAccount()
        h.engine.sync(h.accountId)
        return h
    }

    private fun EngineHarness.actions() =
        ConversationActions(messages, queue, marker, QuietScheduler())

    private suspend fun EngineHarness.row(folder: String, tag: String) =
        messages.identities(accountId, folder).mapNotNull { messages.getById(it.id) }
            .firstOrNull { it.messageId == "<$tag@example.test>" }

    // --- flags (rule 1) ---

    @Test
    fun `the user's flag and the server's other flag both survive`() = runTest {
        val h = start()
        h.actions().setStarred(h.id("INBOX", 1), true)
        h.server.changeFlags("INBOX", 1, MessageFlags(seen = true))

        h.syncUntilDone()

        val flags = h.server.copy("INBOX", "in-1")!!.flags
        assertTrue(flags.flagged && flags.seen)
        val row = h.row("INBOX", "in-1")!!
        assertTrue(row.flagged && row.seen && !row.pendingSync)
    }

    @Test
    fun `the user's last word wins over the server's state for the same flag`() = runTest {
        val h = start()
        // The app knows in-2 as read.
        h.server.changeFlags("INBOX", 2, MessageFlags(seen = true))
        h.syncUntilDone()
        h.actions().markUnread(h.id("INBOX", 2))
        h.actions().markRead(h.id("INBOX", 1))
        // Meanwhile the server stars in-2 and marks in-1 as starred too.
        h.server.changeFlags("INBOX", 2, MessageFlags(seen = true, flagged = true))
        h.server.changeFlags("INBOX", 1, MessageFlags(flagged = true))

        h.syncUntilDone()

        val two = h.server.copy("INBOX", "in-2")!!.flags
        assertEquals(false, two.seen, "the user unread it")
        assertTrue(two.flagged, "the server's star is kept")
        val one = h.server.copy("INBOX", "in-1")!!.flags
        assertTrue(one.seen && one.flagged)
        assertEquals(false, h.row("INBOX", "in-2")!!.seen)
        assertTrue(h.row("INBOX", "in-2")!!.flagged)
    }

    @Test
    fun `a server that already has the user's flag leaves one idempotent send and a settled row`() =
        runTest {
            val h = start()
            h.actions().markRead(h.id("INBOX", 1))
            h.server.changeFlags("INBOX", 1, MessageFlags(seen = true))
            h.server.log.clear()

            h.syncUntilDone()

            assertTrue(h.server.logged("setFlags").size <= 1)
            assertTrue(h.server.copy("INBOX", "in-1")!!.flags.seen)
            assertTrue(h.operations.all(h.accountId).isEmpty())
            assertEquals(false, h.row("INBOX", "in-1")!!.pendingSync)
        }

    @Test
    fun `a flag change on a message the server expunged is dropped with a notice`() = runTest {
        val h = start()
        h.actions().setStarred(h.id("INBOX", 2), true)
        h.server.expunge("INBOX", 2)

        h.syncUntilDone()

        assertNull(h.row("INBOX", "in-2"))
        assertTrue(h.operations.all(h.accountId).isEmpty())
        assertEquals(0, h.server.copies("in-2"))
    }

    // --- move, delete (rule 2) ---

    @Test
    fun `a move of a message the server deleted is dropped and announced`() = runTest {
        val h = start()
        h.actions().move(listOf(h.id("INBOX", 3)), "Archive")
        h.server.expunge("INBOX", 3)

        h.syncUntilDone()

        assertEquals(0, h.server.copies("in-3"))
        assertTrue(h.operations.all(h.accountId).isEmpty())
        assertEquals(1, h.collectNotices().count { it is SyncNotice.MessageVanished })
    }

    @Test
    fun `a move of a message another client already moved to the same folder is not repeated`() =
        runTest {
            val h = start()
            h.actions().move(listOf(h.id("INBOX", 3)), "Archive")
            // The other client moves it to Archive first.
            val header = h.server.folder("INBOX").messages.getValue(3)
            h.server.expunge("INBOX", 3)
            h.server.deliverWithBody("Archive", "in-3", flags = header.flags)

            h.syncUntilDone()

            assertEquals(1, h.server.copies("in-3"))
            assertEquals(
                1,
                h.server.folder("Archive").messages.values.count {
                    it.messageId == "<in-3@example.test>"
                }
            )
            assertTrue(h.operations.all(h.accountId).isEmpty())
            assertTrue(h.collectNotices().none { it is SyncNotice.MessageVanished })
        }

    @Test
    fun `a move of a message another client filed elsewhere loses nothing`() = runTest {
        val h = start()
        h.server.folder("Other")
        h.engine.sync(h.accountId)
        h.actions().move(listOf(h.id("INBOX", 3)), "Archive")
        val header = h.server.folder("INBOX").messages.getValue(3)
        h.server.expunge("INBOX", 3)
        h.server.deliverWithBody("Other", "in-3", flags = header.flags)

        h.syncUntilDone()

        // The message is still on the server exactly once, and the queue is empty.
        assertEquals(1, h.server.copies("in-3"))
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `a delete of a message the server changed meanwhile deletes it`() = runTest {
        val h = start()
        h.queue.enqueue(NewOperation(h.accountId, OperationType.DELETE, "INBOX", 4, ""))
        h.marker.mark(h.accountId, "INBOX", 4)
        h.server.changeFlags("INBOX", 4, MessageFlags(seen = true, flagged = true))

        h.syncUntilDone()

        assertEquals(0, h.server.copies("in-4"))
        assertNull(h.row("INBOX", "in-4"))
    }

    // --- races ---

    @Test
    fun `a refresh while a periodic sync runs does not start a second sync`() = runTest {
        val h = start()
        h.actions().markRead(h.id("INBOX", 1))
        h.server.log.clear()
        h.connector.connects.clear()
        val gate = CompletableDeferred<Unit>()
        h.connector.gate = gate
        val periodic = async { h.engine.sync(h.accountId) }
        testScheduler.runCurrent()

        // The user pulls to refresh while the periodic sync waits on the network.
        val refresh = h.engine.sync(h.accountId, userInitiated = true)
        val connectsWhileBlocked = h.connector.connects.size
        gate.complete(Unit)
        val finished = periodic.await()

        assertEquals(AccountSyncResult.AlreadyRunning, refresh)
        assertTrue(finished is AccountSyncResult.Synced)
        assertEquals(1, connectsWhileBlocked, "only the periodic sync tried to connect")
        assertEquals(1, h.server.logged("setFlags INBOX [1]").size, "sent once")
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `two drains of the queue at the same time send each operation once`() = runTest {
        val h = start()
        h.userActs()
        h.server.log.clear()

        val first = async { h.queue.drain(h.accountId) }
        val second = async { h.queue.drain(h.accountId) }
        first.await()
        second.await()

        assertEquals(1, h.server.logged("setFlags INBOX [1]").size)
        assertEquals(1, h.server.logged("setFlags INBOX [2]").size)
        assertEquals(1, h.server.logged("move INBOX [3]").size)
        assertEquals(1, h.server.logged("delete INBOX [4]").size)
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `a change the user makes in the middle of a pull is not undone by it`() = runTest {
        val h = start()
        // Sent is the last folder pulled, so nothing pushes the change before the sync ends.
        h.server.changeFlags("Sent", 1, MessageFlags(seen = false, flagged = true))
        val id = h.id("Sent", 1)
        var done = false
        // The user reads the message just as the pull asks for the folder's flags.
        h.server.failure = { name ->
            if (!done && name.startsWith("fetchHeaders Sent 1-")) {
                done = true
                // Straight into Room, as the action does (the queue would wait for the test clock).
                runBlocking {
                    h.messages.setFlags(
                        id,
                        seen = true,
                        flagged = false,
                        answered = false,
                        pendingSync = true
                    )
                    h.operations.enqueue(
                        PendingOperationEntity(
                            accountId = h.accountId,
                            type = OperationType.SET_FLAGS,
                            folderPath = "Sent",
                            uid = 1,
                            payload = FlagChange(seen = true).encode(),
                            createdAt = h.clock.instant()
                        )
                    )
                }
            }
            null
        }
        launch { h.engine.sync(h.accountId) }.join()

        val row = h.messages.getById(id)!!
        assertTrue(row.seen, "the user's read mark is still there")
        assertTrue(row.flagged, "the server's star came in")
        assertTrue(row.pendingSync)
        assertNotNull(h.operations.all(h.accountId).singleOrNull())

        h.server.failure = { null }
        h.syncUntilDone()

        assertTrue(h.server.copy("Sent", "se-1")!!.flags.seen)
        assertTrue(h.row("Sent", "se-1")!!.seen)
        assertEquals(false, h.row("Sent", "se-1")!!.pendingSync)
    }

    @Test
    fun `the server refusing a flag with a transient answer keeps the change queued`() = runTest {
        val h = start()
        h.actions().markRead(h.id("INBOX", 1))
        h.server.failure = { name ->
            MailResult.ServerRejected(
                com.qtekfun.ultimatemail.domain.mail.RejectionKind.NO,
                permanent = false
            ).takeIf { name.startsWith("setFlags") }
        }

        h.engine.sync(h.accountId)

        assertEquals(1, h.operations.all(h.accountId).size)
        assertTrue(h.row("INBOX", "in-1")!!.seen, "still shows the user's choice")
        assertTrue(h.row("INBOX", "in-1")!!.pendingSync)
    }
}
