// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.search

import android.content.SharedPreferences
import androidx.core.content.edit
import com.qtekfun.ultimatemail.domain.search.RecentSearchLog
import com.qtekfun.ultimatemail.domain.search.RecentSearches

/**
 * [RecentSearches] kept in SharedPreferences, in a file of its own so "clear all" removes
 * exactly this. Shared preferences are excluded from backups and device transfer (see the
 * backup rules), so the searches stay on this device.
 */
class SharedPreferencesRecentSearches(private val preferences: SharedPreferences) :
    RecentSearches {
    @Synchronized
    override fun recent(): List<String> = load().recent()

    @Synchronized
    override fun record(text: String) = save(load().recorded(text))

    @Synchronized
    override fun remove(text: String) = save(load().removed(text))

    @Synchronized
    override fun clear() = preferences.edit { remove(KEY) }

    private fun load() = RecentSearchLog.decode(preferences.getString(KEY, null))

    private fun save(log: RecentSearchLog) = preferences.edit { putString(KEY, log.encode()) }

    private companion object {
        const val KEY = "recent"
    }
}
