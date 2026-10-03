// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * The per-account switch "Download messages for offline reading" (RF-10): whether sync also
 * downloads the full bodies of the messages inside the offline window. On by default. It lives
 * behind this interface (not in Room) so it needs no schema change.
 */
interface OfflineDownloads {
    fun isEnabled(accountId: Long): Boolean

    /** The current value of [accountId] and every change after. */
    fun observe(accountId: Long): Flow<Boolean>

    fun setEnabled(accountId: Long, enabled: Boolean)

    companion object {
        const val DEFAULT_ENABLED = true
    }
}
