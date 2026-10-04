// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Where the sync of one account stands, for the UI (RF-10). */
sealed interface AccountSyncState {
    /** Not syncing; [lastSyncedAt] is when it last finished well, if it ever did. */
    data class Idle(val lastSyncedAt: Instant? = null) : AccountSyncState

    data object Syncing : AccountSyncState

    /** The folders are being brought down: [done] of [total] have been looked at. */
    data class SyncingFolders(val done: Int, val total: Int) : AccountSyncState

    /**
     * The headers are in; the bodies of the messages inside the offline window are being
     * downloaded: [done] of [total] have theirs (RF-10).
     */
    data class DownloadingBodies(val done: Int, val total: Int) : AccountSyncState

    /** The last attempt failed for [problem]; local data is untouched. */
    data class Error(val problem: SyncProblem) : AccountSyncState

    /**
     * The server refused the credentials or the token was revoked (RF-01): ask the user to sign
     * in again. Nothing local is deleted, and syncing stays paused until the user retries.
     */
    data object ReauthenticationNeeded : AccountSyncState
}

/** What the UI watches to show sync progress and problems per account. */
interface SyncStatus {
    val states: StateFlow<Map<Long, AccountSyncState>>

    fun observe(accountId: Long): Flow<AccountSyncState>
}

/**
 * The [SyncStatus] of the process; the engine and the mail sessions write to it. The time of the
 * last good sync also goes to [lastSync], and an account heard of for the first time since the
 * app started is idle with that time.
 */
@Singleton
class SyncStatusStore @Inject constructor(private val lastSync: LastSyncLog) : SyncStatus {
    /** A store that remembers nothing beyond the process. */
    constructor() : this(LastSyncLog.None)

    private val current = MutableStateFlow<Map<Long, AccountSyncState>>(emptyMap())

    override val states: StateFlow<Map<Long, AccountSyncState>> = current.asStateFlow()

    override fun observe(accountId: Long): Flow<AccountSyncState> =
        current.map { it[accountId] ?: idle(accountId) }

    fun get(accountId: Long): AccountSyncState = current.value[accountId] ?: idle(accountId)

    fun set(accountId: Long, state: AccountSyncState) {
        if (state is AccountSyncState.Idle) state.lastSyncedAt?.let { lastSync.put(accountId, it) }
        current.update { it + (accountId to state) }
    }

    private fun idle(accountId: Long) = AccountSyncState.Idle(lastSync.get(accountId))

    fun remove(accountId: Long) = current.update { it - accountId }

    /**
     * The user signed in again: the account no longer waits for them, so the next sync of any
     * kind tries it. Other states are left alone (a sync may be running).
     */
    fun clearReauthentication(accountId: Long) = current.update {
        if (it[accountId] == AccountSyncState.ReauthenticationNeeded) it - accountId else it
    }
}
