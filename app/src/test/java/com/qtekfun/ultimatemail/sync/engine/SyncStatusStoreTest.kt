// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncStatusStoreTest {
    private val preferences = FakePreferenceStore()
    private val at = Instant.parse("2026-10-04T08:30:00Z")

    private fun store() = SyncStatusStore(PreferenceLastSyncLog(preferences))

    @Test
    fun `an account idle after a restart still knows when it last synced`() {
        store().set(1, AccountSyncState.Idle(at))

        assertEquals(AccountSyncState.Idle(at), store().get(1))
    }

    @Test
    fun `a failed or running sync does not erase the last good time`() {
        val first = store()
        first.set(1, AccountSyncState.Idle(at))
        first.set(1, AccountSyncState.Syncing)
        first.set(1, AccountSyncState.Error(SyncProblem.NETWORK))

        assertEquals(AccountSyncState.Idle(at), store().get(1))
    }

    @Test
    fun `the time is kept per account`() {
        val first = store()
        first.set(1, AccountSyncState.Idle(at))

        assertEquals(AccountSyncState.Idle(null), store().get(2))
    }

    @Test
    fun `without a log nothing outlives the store`() {
        SyncStatusStore().set(1, AccountSyncState.Idle(at))

        assertEquals(AccountSyncState.Idle(null), SyncStatusStore().get(1))
    }
}
