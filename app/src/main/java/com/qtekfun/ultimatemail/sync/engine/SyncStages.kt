// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phases a mailbox is brought down in (RF-10): the last 30 days first, so the lists are
 * usable within seconds, then up to a year, then as far back as the account's offline window
 * goes. A window shorter than a phase ends the plan there. Depths are in days;
 * [WHOLE_MAILBOX] stands for "no limit".
 */
internal object SyncStages {
    const val FIRST_DAYS = 30
    const val SECOND_DAYS = 365
    const val WHOLE_MAILBOX = Int.MAX_VALUE

    /** The depths to reach, shallowest first, for an offline window of [windowDays] (null: all). */
    fun plan(windowDays: Int?): List<Int> {
        val target = windowDays ?: WHOLE_MAILBOX
        return (listOf(FIRST_DAYS, SECOND_DAYS) + target).filter {
            it <= target
        }.distinct().sorted()
    }

    /** The depth of the headers of an account that is not yet fully brought down. */
    fun target(windowDays: Int?): Int = windowDays ?: WHOLE_MAILBOX
}

/**
 * How far back each account has been brought down, kept across restarts so a sync that is cut
 * short (the system stops background work after a while) carries on instead of starting over.
 * Only a number of days per account; nothing about the mail.
 */
interface SyncDepthLog {
    /** Days reached for [accountId]; 0 when nothing has been brought down yet. */
    fun get(accountId: Long): Int

    fun put(accountId: Long, days: Int)

    /** Remembers nothing: every sync starts from the first phase. */
    data object None : SyncDepthLog {
        override fun get(accountId: Long): Int = 0

        override fun put(accountId: Long, days: Int) = Unit
    }
}

/** [SyncDepthLog] on the private preferences of the app. */
@Singleton
class PreferenceSyncDepthLog @Inject constructor(private val preferences: PreferenceStore) :
    SyncDepthLog {
    override fun get(accountId: Long): Int =
        preferences.getString(key(accountId))?.toIntOrNull() ?: 0

    override fun put(accountId: Long, days: Int) =
        preferences.putString(key(accountId), days.toString())

    private fun key(accountId: Long) = "sync_depth_$accountId"
}
