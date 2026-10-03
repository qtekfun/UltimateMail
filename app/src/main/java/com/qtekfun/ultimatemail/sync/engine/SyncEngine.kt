// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * The entry point of synchronisation (RF-10): runs [AccountSync] per account, one at a time per
 * account, and publishes what happens in [SyncStatus]. WorkManager, app open and pull-to-refresh
 * all end up here.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val accounts: AccountDao,
    private val accountSync: AccountSync,
    private val status: SyncStatusStore,
    private val clock: Clock
) {
    private val running = ConcurrentHashMap<Long, Mutex>()

    /** Syncs every account, one after the other. */
    suspend fun syncAll(userInitiated: Boolean = false): SyncReport {
        val ids = accounts.observeAll().first().map { it.id }
        return SyncReport(ids.associateWith { sync(it, userInitiated) })
    }

    /**
     * Syncs one account. While the account waits for the user to sign in again, only a
     * [userInitiated] sync tries it (a wrong password retried every 15 minutes can get an account
     * locked); the others answer [AccountSyncResult.ReauthenticationNeeded] right away.
     */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    suspend fun sync(accountId: Long, userInitiated: Boolean = false): AccountSyncResult {
        val before = status.get(accountId)
        if (!userInitiated && before == AccountSyncState.ReauthenticationNeeded) {
            return AccountSyncResult.ReauthenticationNeeded
        }
        val lock = running.computeIfAbsent(accountId) { Mutex() }
        if (!lock.tryLock()) return AccountSyncResult.AlreadyRunning
        try {
            status.set(accountId, AccountSyncState.Syncing)
            val result = try {
                accountSync.run(accountId)
            } catch (cancelled: CancellationException) {
                status.set(accountId, before)
                throw cancelled
            }
            if (result == AccountSyncResult.NoAccount) {
                status.remove(accountId)
            } else {
                status.set(accountId, result.toState(before))
            }
            return result
        } finally {
            lock.unlock()
        }
    }

    private fun AccountSyncResult.toState(before: AccountSyncState): AccountSyncState =
        when (this) {
            is AccountSyncResult.Synced -> AccountSyncState.Idle(clock.instant())
            is AccountSyncResult.Failed -> AccountSyncState.Error(problem)
            AccountSyncResult.ReauthenticationNeeded -> AccountSyncState.ReauthenticationNeeded
            AccountSyncResult.AlreadyRunning, AccountSyncResult.NoAccount -> before
        }
}
