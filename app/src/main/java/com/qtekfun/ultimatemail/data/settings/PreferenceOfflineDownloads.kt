// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** [OfflineDownloads] kept in a [PreferenceStore], one boolean per account id. */
@Singleton
class PreferenceOfflineDownloads @Inject constructor(private val store: PreferenceStore) :
    OfflineDownloads {
    override fun isEnabled(accountId: Long): Boolean =
        store.getBoolean(key(accountId), OfflineDownloads.DEFAULT_ENABLED)

    override fun observe(accountId: Long): Flow<Boolean> = store.changes()
        .onStart { emit(Unit) }
        .map { isEnabled(accountId) }
        .distinctUntilChanged()

    override fun setEnabled(accountId: Long, enabled: Boolean) =
        store.putBoolean(key(accountId), enabled)

    private fun key(accountId: Long) = "offline_bodies_$accountId"
}
