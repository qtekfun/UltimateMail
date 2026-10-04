// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.sync.engine.AccountSyncState

/**
 * The one sync line under the title of a list. A list can span several accounts (the unified
 * inbox), so the most urgent thing wins: sign in again, then an error, then the progress of a
 * sync still running; when all are idle it is the oldest last-sync time, the honest "updated at".
 */
object InboxSyncLine {
    fun of(states: List<AccountSyncState>): SyncLine {
        val lines = states.map { SyncLine.of(it) }
        return lines.firstOrNull { it.needsSignIn }
            ?: lines.firstOrNull { it is SyncLine.Failed }
            ?: lines.firstOrNull { it.isRunning }
            ?: lines.filterIsInstance<SyncLine.LastSynced>().minByOrNull { it.at }
                .takeIf { lines.none { it == SyncLine.NeverSynced } }
            ?: SyncLine.NeverSynced
    }

    private val SyncLine.isRunning: Boolean
        get() = this == SyncLine.Syncing || this is SyncLine.SyncingFolders ||
            this is SyncLine.DownloadingMessages
}
