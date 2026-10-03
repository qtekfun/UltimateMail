// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC section 5, rule 4: a UIDVALIDITY change with the user's changes still queued. */
class ResetWithPendingTest {
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

    @Test
    fun `flag and move queued before a reset reach the same messages after renumbering`() =
        runTest {
            val h = start()
            h.userActs() // read in-1, star in-2, move in-3 to Archive, delete in-4
            // Mail came and went meanwhile, so every surviving message gets another UID.
            h.server.expunge("INBOX", 1)
            h.server.deliverWithBody("INBOX", "in-5")
            h.server.renumber("INBOX")

            h.syncUntilDone()
            h.syncUntilDone()

            // in-1 was read by the user but is gone from the server: nothing to flag.
            assertNull(h.server.copy("INBOX", "in-1"))
            assertTrue(h.server.copy("INBOX", "in-2")!!.flags.flagged)
            assertNull(h.server.copy("INBOX", "in-3"), "moved out of INBOX")
            assertNotNull(h.server.copy("Archive", "in-3"), "and into Archive")
            assertEquals(0, h.server.copies("in-4"), "deleted")
            assertEquals(1, h.server.copies("in-3"), "never duplicated")
            assertEquals(false, h.server.copy("INBOX", "in-5")!!.flags.flagged)
            assertTrue(h.operations.all(h.accountId).isEmpty())
            val notices = h.collectNotices()
            assertTrue(notices.any { it is SyncNotice.FolderReset })
            assertEquals(1, notices.count { it is SyncNotice.MessageVanished }, "only in-1")
        }

    @Test
    fun `a flag lands on the message and not on whoever now holds the old UID`() = runTest {
        val h = start()
        val actions = com.qtekfun.ultimatemail.domain.conversation.ConversationActions(
            h.messages,
            h.queue,
            h.marker,
            QuietScheduler()
        )
        actions.setStarred(h.id("INBOX", 3), true)
        // The server reshuffles: in-3 now has UID 2, and UID 3 belongs to in-4.
        h.server.expunge("INBOX", 2)
        h.server.renumber("INBOX")

        h.syncUntilDone()
        h.syncUntilDone()

        assertTrue(h.server.copy("INBOX", "in-3")!!.flags.flagged)
        assertEquals(false, h.server.copy("INBOX", "in-4")!!.flags.flagged)
        assertEquals(false, h.server.copy("INBOX", "in-1")!!.flags.flagged)
        val row = h.messages.identities(h.accountId, "INBOX")
            .mapNotNull {
                h.messages.getById(it.id)
            }.first { it.messageId == "<in-3@example.test>" }
        assertTrue(row.flagged)
        assertEquals(false, row.pendingSync)
    }

    @Test
    fun `a reset of the destination folder does not lose a move waiting for it`() = runTest {
        val h = start()
        h.userActs()
        h.server.renumber("Archive")
        h.server.renumber("INBOX")

        h.syncUntilDone()
        h.syncUntilDone()

        assertEquals(1, h.server.copies("in-3"))
        assertNotNull(h.server.copy("Archive", "in-3"))
        assertTrue(h.operations.all(h.accountId).isEmpty())
    }

    @Test
    fun `the pending indicator stays on until the change is on the server after a reset`() =
        runTest {
            val h = start()
            h.userActs()
            h.server.renumber("INBOX")
            // The pull works but pushing flags fails.
            h.server.failure = { name ->
                com.qtekfun.ultimatemail.domain.mail.MailResult.NetworkUnavailable
                    .takeIf { name.startsWith("setFlags") }
            }

            h.engine.sync(h.accountId)

            val flagged = h.messages.identities(h.accountId, "INBOX")
                .mapNotNull { h.messages.getById(it.id) }
                .first { it.messageId == "<in-2@example.test>" }
            assertTrue(flagged.flagged, "the user's change shows over the fresh state")
            assertTrue(flagged.pendingSync, "and is marked as not yet on the server")
        }
}
