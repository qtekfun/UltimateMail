// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

/**
 * When syncs run (RF-10): periodically in the background and on demand. The engine only knows
 * this interface, so it is tested without WorkManager; the real one is in sync.worker.
 */
interface SyncScheduler {
    /** Keeps a sync running every ~15 minutes while there is network. Safe to call repeatedly. */
    fun startPeriodic()

    /**
     * Syncs as soon as there is network: app open, pull-to-refresh. [accountId] null means every
     * account. A request made while one is waiting is merged into it. [userInitiated] also retries
     * accounts that are waiting for the user to sign in again.
     */
    fun requestSync(accountId: Long? = null, userInitiated: Boolean = false)

    /** Stops the periodic sync and any waiting request. */
    fun stop()
}
