// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

/** A [RecentSearches] that lives in memory, for tests. */
class InMemoryRecentSearches(initial: List<String> = emptyList()) : RecentSearches {
    private var log = initial.reversed().fold(RecentSearchLog.EMPTY) { acc, text ->
        acc.recorded(text)
    }

    override fun recent(): List<String> = log.recent()

    override fun record(text: String) {
        log = log.recorded(text)
    }

    override fun remove(text: String) {
        log = log.removed(text)
    }

    override fun clear() {
        log = RecentSearchLog.EMPTY
    }
}
