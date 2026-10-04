// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import java.time.Instant

/** The one-line sync status at the bottom of the folder menu (RF-10). */
sealed interface SyncLine {
    /** Nothing is known: no sync has finished yet. */
    data object NeverSynced : SyncLine

    data object Syncing : SyncLine

    /** Folder [done] of [total] is being synced (a mailbox can have hundreds of labels). */
    data class SyncingFolders(val done: Int, val total: Int) : SyncLine

    /** Headers are in; [done] of [total] message bodies are on the device. */
    data class DownloadingMessages(val done: Int, val total: Int) : SyncLine

    data class LastSynced(val at: Instant) : SyncLine

    /** The credentials were refused; the user has to sign in again. */
    data object SignInAgain : SyncLine

    data class Failed(val problem: SyncProblem) : SyncLine

    /** True when the line is a call to action: the user can fix it by signing in again (T27). */
    val needsSignIn: Boolean get() = this == SignInAgain

    companion object {
        /** The line for the sync state of an account; an account never heard of counts as idle. */
        fun of(state: AccountSyncState?): SyncLine = when (state) {
            null -> NeverSynced
            is AccountSyncState.Idle -> state.lastSyncedAt?.let(::LastSynced) ?: NeverSynced
            AccountSyncState.Syncing -> Syncing
            is AccountSyncState.SyncingFolders -> SyncingFolders(state.done, state.total)
            is AccountSyncState.DownloadingBodies -> DownloadingMessages(state.done, state.total)
            is AccountSyncState.Error -> Failed(state.problem)
            AccountSyncState.ReauthenticationNeeded -> SignInAgain
        }
    }
}
