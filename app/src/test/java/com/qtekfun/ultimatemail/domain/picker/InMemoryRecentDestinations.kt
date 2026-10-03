// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

/** A [RecentDestinations] that lives in memory, for tests. */
class InMemoryRecentDestinations : RecentDestinations {
    private val logs = mutableMapOf<Long, RecentLog>()

    /** Every call to [record], in order. */
    val recorded = mutableListOf<Pair<Long, List<String>>>()

    override fun recent(accountId: Long): List<String> = log(accountId).recent()

    override fun usage(accountId: Long): Map<String, Int> = log(accountId).usage()

    override fun record(accountId: Long, paths: List<String>) {
        recorded += accountId to paths
        logs[accountId] = log(accountId).recorded(paths)
    }

    private fun log(accountId: Long) = logs[accountId] ?: RecentLog.Empty
}
