// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/**
 * Asks for a sync, for pull-to-refresh. The list itself always follows Room, so a refresh only
 * has to make the sync engine (T10) fetch new mail; it must not throw (the engine reports
 * failures through the sync status) and returns when the sync it started has finished.
 */
fun interface RefreshTrigger {
    /** Syncs [accountId], or every account when it is null (the unified inbox). */
    suspend fun refresh(accountId: Long?)
}
