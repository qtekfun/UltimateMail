// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The connection drops at every step of a pull; the next run reaches the uninterrupted state. */
class PullDropsTest {
    private val opened = mutableListOf<EngineHarness>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private suspend fun TestScope.start(setup: EngineHarness.() -> Unit): EngineHarness {
        val h = EngineHarness(this)
        opened += h
        h.server.populate()
        h.addAccount()
        h.offlineDownloads.setEnabled(h.accountId, true)
        h.setup()
        return h
    }

    /** The server changes between two syncs: new mail, flags, an expunge. */
    private fun EngineHarness.serverMoves() {
        server.deliverWithBody("INBOX", "in-5")
        server.deliverWithBody("INBOX", "in-6")
        server.changeFlags("INBOX", 1, MessageFlags(seen = true, flagged = true))
        server.expunge("INBOX", 2)
        server.changeFlags("Archive", 1, MessageFlags(seen = true))
    }

    private suspend fun TestScope.reference(
        second: Boolean
    ): Pair<RoomState, Map<String, List<String>>> {
        val h = start {}
        h.engine.sync(h.accountId)
        if (second) {
            h.serverMoves()
            h.waitOutBackoff()
            h.engine.sync(h.accountId)
        }
        return h.roomState() to h.server.contents()
    }

    /** Cuts at every call of the sync (before or after the server applied it) and converges. */
    private suspend fun TestScope.sweep(second: Boolean) {
        val (expected, expectedServer) = reference(second)
        val probe = start {}
        if (second) {
            probe.engine.sync(probe.accountId)
            probe.serverMoves()
        }
        probe.server.log.clear()
        probe.engine.sync(probe.accountId)
        val total = probe.server.calls().size
        assertTrue(total >= 8, "the scenario must have many steps, had $total")
        for (n in 1..total) {
            val h = start {}
            val net = FlakyNetwork(h)
            if (second) {
                h.engine.sync(h.accountId)
                h.serverMoves()
            }
            net.dropBefore(n)
            val cut = h.engine.sync(h.accountId)
            assertTrue(net.isDown, "drop $n/$total was not reached")
            assertTrue(cut is AccountSyncResult.Failed, "drop $n/$total: $cut")
            net.restore()
            h.syncUntilDone()
            assertEquals(expected, h.roomState(), "Room after a drop at call $n/$total")
            assertEquals(expectedServer, h.server.contents(), "server after drop $n/$total")
        }
    }

    @Test
    fun `a drop at any step of the first sync still converges to the uninterrupted state`() =
        runTest { sweep(second = false) }

    @Test
    fun `a drop at any step of an incremental sync still converges`() =
        runTest { sweep(second = true) }

    @Test
    fun `a drop right after listing the folders keeps the list and the next run fills it`() =
        runTest {
            val h = start {}
            val net = FlakyNetwork(h)
            net.dropBefore(2) // listFolders is call 1
            h.engine.sync(h.accountId)
            assertEquals(0, h.roomState().messages.size)
            assertEquals(3, h.folders.all(h.accountId).size, "the folder list was stored")
            net.restore()
            h.syncUntilDone()
            assertEquals(7, h.roomState().messages.size)
        }
}
