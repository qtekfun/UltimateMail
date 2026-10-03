// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

/** How many messages a pull added, changed (flags, labels) and removed. */
data class SyncCounts(val added: Int = 0, val updated: Int = 0, val removed: Int = 0) {
    operator fun plus(other: SyncCounts) =
        SyncCounts(added + other.added, updated + other.updated, removed + other.removed)
}

/** How the sync of one account ended. */
sealed interface AccountSyncResult {
    data class Synced(val counts: SyncCounts) : AccountSyncResult

    data class Failed(val problem: SyncProblem) : AccountSyncResult

    /** The user has to sign in again; nothing was deleted (RF-01). */
    data object ReauthenticationNeeded : AccountSyncResult

    /** Another sync of the same account was already running; it does the work. */
    data object AlreadyRunning : AccountSyncResult

    data object NoAccount : AccountSyncResult
}

/** The outcome of syncing every account. */
data class SyncReport(val results: Map<Long, AccountSyncResult>) {
    /** True when WorkManager should try again later with backoff. */
    val shouldRetry: Boolean
        get() = results.values.any { it is AccountSyncResult.Failed && it.problem.retryable }
}
