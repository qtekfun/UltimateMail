// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When each account last finished a sync well, kept across restarts so the folder menu does not
 * say "not synced yet" every time the app starts (RF-10). Only the time is kept, never anything
 * about the mail.
 */
interface LastSyncLog {
    fun get(accountId: Long): Instant?

    fun put(accountId: Long, at: Instant)

    /** Remembers nothing: for the status store when nothing needs to outlive the process. */
    data object None : LastSyncLog {
        override fun get(accountId: Long): Instant? = null

        override fun put(accountId: Long, at: Instant) = Unit
    }
}

/** [LastSyncLog] on the private preferences of the app. */
@Singleton
class PreferenceLastSyncLog @Inject constructor(private val preferences: PreferenceStore) :
    LastSyncLog {
    override fun get(accountId: Long): Instant? = preferences.getString(key(accountId))
        ?.toLongOrNull()
        ?.let(Instant::ofEpochMilli)

    override fun put(accountId: Long, at: Instant) =
        preferences.putString(key(accountId), at.toEpochMilli().toString())

    private fun key(accountId: Long) = "last_sync_$accountId"
}
