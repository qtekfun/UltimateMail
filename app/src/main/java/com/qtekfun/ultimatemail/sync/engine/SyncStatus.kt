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

/** The in-memory [SyncStatus]; the engine and the mail sessions write to it. */
@Singleton
class SyncStatusStore @Inject constructor() : SyncStatus {
    private val current = MutableStateFlow<Map<Long, AccountSyncState>>(emptyMap())

    override val states: StateFlow<Map<Long, AccountSyncState>> = current.asStateFlow()

    override fun observe(accountId: Long): Flow<AccountSyncState> =
        current.map { it[accountId] ?: AccountSyncState.Idle() }

    fun get(accountId: Long): AccountSyncState = current.value[accountId] ?: AccountSyncState.Idle()

    fun set(accountId: Long, state: AccountSyncState) = current.update { it + (accountId to state) }

    fun remove(accountId: Long) = current.update { it - accountId }
}
