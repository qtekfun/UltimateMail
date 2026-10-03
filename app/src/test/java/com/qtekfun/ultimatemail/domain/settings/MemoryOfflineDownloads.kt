// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** [OfflineDownloads] in memory, for tests; accounts not set read as [default]. */
class MemoryOfflineDownloads(private val default: Boolean = OfflineDownloads.DEFAULT_ENABLED) :
    OfflineDownloads {
    private val values = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

    override fun isEnabled(accountId: Long) = values.value[accountId] ?: default

    override fun observe(accountId: Long): Flow<Boolean> =
        values.map { it[accountId] ?: default }.distinctUntilChanged()

    override fun setEnabled(accountId: Long, enabled: Boolean) =
        values.update { it + (accountId to enabled) }
}
