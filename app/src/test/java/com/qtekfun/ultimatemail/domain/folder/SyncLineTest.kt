// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncLineTest {
    @Test
    fun `an account without status or without a finished sync was never synced`() {
        assertEquals(SyncLine.NeverSynced, SyncLine.of(null))
        assertEquals(SyncLine.NeverSynced, SyncLine.of(AccountSyncState.Idle()))
    }

    @Test
    fun `an idle account shows when it last synced`() {
        val at = Instant.parse("2026-03-01T10:15:00Z")

        assertEquals(SyncLine.LastSynced(at), SyncLine.of(AccountSyncState.Idle(at)))
    }

    @Test
    fun `a running sync is shown as syncing`() {
        assertEquals(SyncLine.Syncing, SyncLine.of(AccountSyncState.Syncing))
    }

    @Test
    fun `refused credentials ask to sign in again`() {
        assertEquals(SyncLine.SignInAgain, SyncLine.of(AccountSyncState.ReauthenticationNeeded))
    }

    @Test
    fun `a failed sync keeps the problem`() {
        SyncProblem.entries.forEach { problem ->
            assertEquals(SyncLine.Failed(problem), SyncLine.of(AccountSyncState.Error(problem)))
        }
    }
}
