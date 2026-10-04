// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import java.time.Duration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhasedSyncTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(
        windowDays: Int?,
        setup: EngineHarness.() -> Unit = {}
    ): EngineHarness {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.setup()
        h.addAccount()
        h.db.accountDao().update(
            h.db.accountDao().get(h.accountId)!!.copy(offlineWindowDays = windowDays)
        )
        return h
    }

    private fun EngineHarness.ago(days: Long) = clock.now.minus(Duration.ofDays(days))

    private suspend fun EngineHarness.subjects(): List<String> =
        messages.serverUids(accountId, "INBOX").map {
            messages.get(accountId, "INBOX", it)!!.subject
        }

    @Test
    fun `the phases are 30 days, a year and then the whole window, never past it`() {
        assertEquals(listOf(30, 90), SyncStages.plan(90))
        assertEquals(listOf(30), SyncStages.plan(30))
        assertEquals(listOf(7), SyncStages.plan(7))
        assertEquals(listOf(30, 180), SyncStages.plan(180))
        assertEquals(listOf(30, 365), SyncStages.plan(365))
        assertEquals(listOf(30, 365, SyncStages.WHOLE_MAILBOX), SyncStages.plan(null))
    }

    @Test
    fun `an account with a long window ends up with its whole window, recent mail first`() =
        runTest {
            val h = start(windowDays = null) {
                server.deliver("INBOX", subject = "ancient", sentAt = ago(900))
                server.deliver("INBOX", subject = "last year", sentAt = ago(200))
                server.deliver("INBOX", subject = "recent", sentAt = ago(5))
            }

            val result = h.engine.sync(h.accountId)

            assertTrue(result is AccountSyncResult.Synced)
            assertEquals(setOf("ancient", "last year", "recent"), h.subjects().toSet())
            assertEquals(SyncStages.WHOLE_MAILBOX, h.depth.get(h.accountId))
        }

    @Test
    fun `the first phase stores the recent mail even if a deeper phase then fails`() = runTest {
        val h = start(windowDays = 365) {
            server.deliver("INBOX", subject = "old", sentAt = ago(200))
            server.deliver("INBOX", subject = "recent", sentAt = ago(5))
        }
        // The first phase fetches the folder once; the deeper one fails on its own fetch.
        var fetches = 0
        h.server.failure = { name ->
            if (name.startsWith("fetchHeaders") && ++fetches > 1) {
                MailResult.ServerRejected(
                    com.qtekfun.ultimatemail.domain.mail.RejectionKind.NO,
                    permanent = false
                )
            } else {
                null
            }
        }

        val result = h.engine.sync(h.accountId)

        assertTrue(result is AccountSyncResult.Synced, "the lists work, so it is not a failed sync")
        assertEquals(listOf("recent"), h.subjects())
        assertEquals(SyncStages.FIRST_DAYS, h.depth.get(h.accountId))
    }

    @Test
    fun `a deeper phase that was held back is done by the next sync, once`() = runTest {
        val h = start(windowDays = 365) {
            server.deliver("INBOX", subject = "old", sentAt = ago(200))
            server.deliver("INBOX", subject = "recent", sentAt = ago(5))
        }
        var fetches = 0
        h.server.failure = { name ->
            if (name.startsWith("fetchHeaders") && ++fetches == 2) {
                MailResult.ServerRejected(
                    com.qtekfun.ultimatemail.domain.mail.RejectionKind.NO,
                    permanent = false
                )
            } else {
                null
            }
        }
        h.engine.sync(h.accountId)
        assertEquals(listOf("recent"), h.subjects())

        h.engine.sync(h.accountId)

        assertEquals(setOf("old", "recent"), h.subjects().toSet())
        assertEquals(365, h.depth.get(h.accountId))
        // A regular sync still checks the flags of what it holds; once the depth is reached
        // that is all it does, so two more syncs read exactly the same.
        val first = h.server.logged("fetchHeaders").size
        h.engine.sync(h.accountId)
        val second = h.server.logged("fetchHeaders").size
        h.engine.sync(h.accountId)
        assertEquals(second - first, h.server.logged("fetchHeaders").size - second)
    }

    @Test
    fun `a window of 30 days is one phase and never reads older mail`() = runTest {
        val h = start(windowDays = 30) {
            server.deliver("INBOX", subject = "old", sentAt = ago(200))
            server.deliver("INBOX", subject = "recent", sentAt = ago(5))
        }

        h.engine.sync(h.accountId)

        assertEquals(listOf("recent"), h.subjects())
        assertEquals(30, h.depth.get(h.accountId))
    }

    @Test
    fun `widening the window later brings the older mail down without a reset`() = runTest {
        val h = start(windowDays = 30) {
            server.deliver("INBOX", subject = "old", sentAt = ago(200))
            server.deliver("INBOX", subject = "recent", sentAt = ago(5))
        }
        h.engine.sync(h.accountId)
        h.db.accountDao().update(h.db.accountDao().get(h.accountId)!!.copy(offlineWindowDays = 365))

        h.engine.sync(h.accountId)

        assertEquals(setOf("old", "recent"), h.subjects().toSet())
        assertEquals(365, h.depth.get(h.accountId))
    }

    @Test
    fun `a connection lost during a deeper phase fails the sync and keeps what was reached`() =
        runTest {
            val h = start(windowDays = 365) {
                server.deliver("INBOX", subject = "old", sentAt = ago(200))
                server.deliver("INBOX", subject = "recent", sentAt = ago(5))
            }
            var fetches = 0
            h.server.failure = { name ->
                if (name.startsWith("fetchHeaders") && ++fetches > 1) {
                    MailResult.NetworkUnavailable
                } else {
                    null
                }
            }

            val result = h.engine.sync(h.accountId)

            assertTrue(result is AccountSyncResult.Failed)
            assertEquals(listOf("recent"), h.subjects())
            assertEquals(SyncStages.FIRST_DAYS, h.depth.get(h.accountId))
        }

    @Test
    fun `the depth is kept per account in the preferences`() {
        val preferences = com.qtekfun.ultimatemail.data.settings.FakePreferenceStore()

        PreferenceSyncDepthLog(preferences).put(4, 365)

        assertEquals(365, PreferenceSyncDepthLog(preferences).get(4))
        assertEquals(0, PreferenceSyncDepthLog(preferences).get(5))
        assertEquals(0, SyncDepthLog.None.get(4))
    }
}
