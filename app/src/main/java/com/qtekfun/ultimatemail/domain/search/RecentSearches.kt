// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

/**
 * The searches the user ran lately, kept on this device only. Only the text of the search is
 * kept (no results, no scope, no account), at most [RecentSearchLog.MAX] of them.
 */
interface RecentSearches {
    /** Most recent first. */
    fun recent(): List<String>

    /** Remembers [text] as the latest search; blank text is not remembered. */
    fun record(text: String)

    /** Forgets one search. */
    fun remove(text: String)

    fun clear()
}

/**
 * The list logic behind [RecentSearches], free of storage: newest first, no duplicates (blanks
 * and case do not count), a fixed maximum.
 */
class RecentSearchLog private constructor(private val entries: List<String>) {
    fun recent(): List<String> = entries

    fun recorded(text: String): RecentSearchLog {
        val clean = normalize(text)
        if (clean.isEmpty()) return this
        val rest = entries.filterNot { it.equals(clean, ignoreCase = true) }
        return RecentSearchLog((listOf(clean) + rest).take(MAX))
    }

    fun removed(text: String): RecentSearchLog {
        val clean = normalize(text)
        return RecentSearchLog(entries.filterNot { it.equals(clean, ignoreCase = true) })
    }

    /** One search per line: a search never holds a line break, since [normalize] removes them. */
    fun encode(): String = entries.joinToString("\n")

    companion object {
        const val MAX = 10
        val EMPTY = RecentSearchLog(emptyList())

        /** The log for text produced by [encode]; anything unreadable counts as empty. */
        fun decode(text: String?): RecentSearchLog {
            if (text.isNullOrEmpty()) return EMPTY
            val entries = text.split('\n').map(::normalize).filter { it.isNotEmpty() }
            return RecentSearchLog(entries.distinctBy { it.lowercase() }.take(MAX))
        }

        private val BLANKS = Regex("\\s+")

        /** Line breaks and runs of blanks become one space; the ends are trimmed. */
        fun normalize(text: String): String = text.replace(BLANKS, " ").trim()
    }
}
