// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.picker

import android.content.SharedPreferences
import androidx.core.content.edit
import com.qtekfun.ultimatemail.domain.picker.RecentDestinations
import com.qtekfun.ultimatemail.domain.picker.RecentLog

/**
 * [RecentDestinations] kept in SharedPreferences, one entry per account. The content is only
 * folder paths with counters.
 */
class SharedPreferencesRecentDestinations(private val preferences: SharedPreferences) :
    RecentDestinations {
    override fun recent(accountId: Long): List<String> = load(accountId).recent()

    override fun usage(accountId: Long): Map<String, Int> = load(accountId).usage()

    @Synchronized
    override fun record(accountId: Long, paths: List<String>) {
        if (paths.isEmpty()) return
        val next = load(accountId).recorded(paths)
        preferences.edit { putString(key(accountId), next.encode()) }
    }

    private fun load(accountId: Long) =
        RecentLog.decode(preferences.getString(key(accountId), null))

    private fun key(accountId: Long) = "recent.$accountId"
}
