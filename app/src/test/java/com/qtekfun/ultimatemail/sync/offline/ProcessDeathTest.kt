// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app is killed in the middle of a sync; a brand-new engine over the same Room recovers. */
class ProcessDeathTest {
    private val opened = mutableListOf<EngineHarness>()

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private suspend fun TestScope.start(synced: Boolean = true): EngineHarness {
        val h = EngineHarness(this)
        opened += h
        h.server.populate()
        h.addAccount()
        h.offlineDownloads.setEnabled(h.accountId, true)
        if (synced) h.engine.sync(h.accountId)
        return h
    }

    /** The server meanwhile: new mail, a flag change, and the UID numbering lost if [reset]. */
    private fun EngineHarness.serverMoves(reset: Boolean) {
        server.deliverWithBody("INBOX", "in-5")
        server.changeFlags("Archive", 2, MessageFlags(flagged = true))
        if (reset) server.renumber("INBOX")
    }

    private suspend fun TestScope.reference(
        reset: Boolean,
        userActs: Boolean
    ): Pair<RoomState, Map<String, List<String>>> {
        val h = start()
        if (userActs) h.userActs()
        h.serverMoves(reset)
        // After a reset the queued changes wait for the pull, so a calm run needs two syncs.
        h.syncUntilDone()
        h.syncUntilDone()
        return h.roomState() to h.server.contents()
    }

    /** Kills at every server call, restarts the app, and checks it ends where a calm run does. */
    private suspend fun TestScope.sweep(afterApplied: Boolean, reset: Boolean) {
        val (expected, expectedServer) = reference(reset, userActs = true)
        val probe = start()
        probe.userActs()
        probe.serverMoves(reset)
        probe.server.log.clear()
        probe.engine.sync(probe.accountId)
        val total = probe.server.calls().size
        for (n in 1..total) {
            val h = start()
            val killer = Killer(h)
            h.userActs()
            h.serverMoves(reset)
            if (afterApplied) killer.killAfter(n) else killer.killBefore(n)
            val died = killer.died { h.engine.sync(h.accountId) }
            assertTrue(died, "the kill at call $n/$total was not reached")
            killer.disarm()
            val label = "kill ${if (afterApplied) "after" else "before"} $n/$total, reset=$reset"

            val second = RestartedApp(h, this)
            h.waitOutBackoff()
            val result = second.engine.sync(h.accountId)
            assertTrue(result is AccountSyncResult.Synced, "$label: $result")
            h.waitOutBackoff()
            second.engine.sync(h.accountId)

            assertEquals(expectedServer, h.server.contents(), "server, $label")
            assertEquals(expected, h.roomState(), "Room, $label")
            assertTrue(h.operations.all(h.accountId).isEmpty(), "queue empty, $label")
            assertTrue(h.messages.pendingUids(h.accountId, "INBOX").isEmpty(), "flags, $label")
            assertTrue(h.messages.pendingUids(h.accountId, "Archive").isEmpty(), "flags, $label")
        }
    }

    @Test
    fun `a kill before any call of a sync with queued changes recovers on a new engine`() =
        runTest { sweep(afterApplied = false, reset = false) }

    @Test
    fun `a kill after the server applied a call but before the app noticed recovers`() =
        runTest { sweep(afterApplied = true, reset = false) }

    @Test
    fun `a kill during a UIDVALIDITY reset with queued changes recovers and keeps them`() =
        runTest { sweep(afterApplied = false, reset = true) }

    @Test
    fun `a kill after the server applied a call during a reset recovers`() =
        runTest { sweep(afterApplied = true, reset = true) }

    @Test
    fun `an operation killed while handed over stays queued as started and is sent again`() =
        runTest {
            val h = start()
            h.userActs()
            val killer = Killer(h)
            killer.killAfter(1) // the first call of the push: the server applies it, then death
            assertTrue(killer.died { h.engine.sync(h.accountId) })
            killer.disarm()

            val waiting = h.operations.all(h.accountId)
            assertEquals(5, waiting.size, "nothing was acknowledged, so nothing left the queue")
            assertTrue(
                waiting.first().startedAt != null,
                "the operation handed over is marked as started"
            )
            assertEquals(0, waiting.first().attempts, "a death is not a counted failed attempt")
            assertTrue(
                h.messages.pendingUids(h.accountId, "INBOX").isNotEmpty(),
                "the pending indicator survives the death"
            )

            h.server.log.clear()
            RestartedApp(h, this).engine.sync(h.accountId)

            assertTrue(h.operations.all(h.accountId).isEmpty())
            assertEquals(1, h.server.logged("setFlags INBOX [1]").size, "sent again, once")
            assertTrue(h.server.folder("INBOX").messages.getValue(1).flags.seen)
        }

    @Test
    fun `body downloads resume after a kill without fetching the stored bodies again`() =
        runTest {
            val h = start(synced = false)
            val killer = Killer(h)
            // Calls: listFolders, 3 x (status, headers), then the bodies, newest first.
            killer.killBefore(9)
            assertTrue(killer.died { h.engine.sync(h.accountId) })
            killer.disarm()
            val stored = h.roomState().messages.count { !it.contains("text=null") }
            assertTrue(stored in 1..6, "some bodies are stored, not all: $stored")
            h.server.log.clear()

            val result = RestartedApp(h, this).engine.sync(h.accountId)

            assertTrue(result is AccountSyncResult.Synced)
            assertEquals(7 - stored, h.server.logged("fetchBody").size, "only what was missing")
            assertEquals(0, h.roomState().messages.count { it.contains("text=null") })
        }

    @Test
    fun `cancelling the sync in the middle of the bodies keeps what came and a new engine ends it`() =
        runTest {
            val h = start(synced = false)
            lateinit var job: Job
            var fetches = 0
            h.server.failure = { name ->
                if (name.startsWith("fetchBody") && ++fetches == 3) job.cancel()
                null
            }
            job = launch { h.engine.sync(h.accountId) }
            job.join()
            assertTrue(job.isCancelled)
            h.server.failure = { null }
            assertTrue(h.roomState().messages.any { it.contains("text=null") })
            assertTrue(h.operations.all(h.accountId).isEmpty())

            val result = RestartedApp(h, this).engine.sync(h.accountId)

            assertTrue(result is AccountSyncResult.Synced)
            assertEquals(0, h.roomState().messages.count { it.contains("text=null") })
        }
}
