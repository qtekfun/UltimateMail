// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InboxSyncLineTest {
    private val morning = Instant.parse("2026-10-04T08:00:00Z")
    private val noon = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun `no accounts means nothing to say`() {
        assertEquals(SyncLine.NeverSynced, InboxSyncLine.of(emptyList()))
    }

    @Test
    fun `one idle account shows when it last synced`() {
        assertEquals(
            SyncLine.LastSynced(noon),
            InboxSyncLine.of(listOf(AccountSyncState.Idle(noon)))
        )
    }

    @Test
    fun `several idle accounts show the oldest time`() {
        val line = InboxSyncLine.of(
            listOf(AccountSyncState.Idle(noon), AccountSyncState.Idle(morning))
        )

        assertEquals(SyncLine.LastSynced(morning), line)
    }

    @Test
    fun `an account never synced hides the time of the others`() {
        val line = InboxSyncLine.of(listOf(AccountSyncState.Idle(noon), AccountSyncState.Idle()))

        assertEquals(SyncLine.NeverSynced, line)
    }

    @Test
    fun `a sync in progress wins over times and shows its progress`() {
        val running = listOf(AccountSyncState.Idle(noon), AccountSyncState.DownloadingBodies(3, 10))
        assertEquals(SyncLine.DownloadingMessages(3, 10), InboxSyncLine.of(running))
        assertEquals(SyncLine.Syncing, InboxSyncLine.of(listOf(AccountSyncState.Syncing)))
        assertEquals(
            SyncLine.SyncingFolders(1, 4),
            InboxSyncLine.of(listOf(AccountSyncState.SyncingFolders(1, 4)))
        )
    }

    @Test
    fun `an error wins over progress and signing in again wins over everything`() {
        val failed = AccountSyncState.Error(SyncProblem.NETWORK)
        val states = listOf(AccountSyncState.Syncing, failed)
        assertEquals(SyncLine.Failed(SyncProblem.NETWORK), InboxSyncLine.of(states))

        val reauth = states + AccountSyncState.ReauthenticationNeeded
        assertEquals(SyncLine.SignInAgain, InboxSyncLine.of(reauth))
    }
}
