// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

/**
 * Where the user last moved or labelled mail, per account (RF-06). Only folder paths are kept,
 * never anything about the messages. Cheap enough to call on the main thread's heels, but the
 * view model calls it off the main thread anyway.
 */
interface RecentDestinations {
    /** Folder paths of [accountId], the most recently used first. */
    fun recent(accountId: Long): List<String>

    /** How many times each folder path of [accountId] was used, to order equal search matches. */
    fun usage(accountId: Long): Map<String, Int>

    /** Notes that the folders at [paths] were used now; the last of them is the most recent. */
    fun record(accountId: Long, paths: List<String>)
}

/**
 * The history behind [RecentDestinations] as a value: pure, so the rules are tested without
 * Android, and it encodes to the text a store keeps.
 */
class RecentLog private constructor(private val entries: List<Entry>) {
    private class Entry(val path: String, val count: Int, val last: Long)

    /** Paths, most recent first. */
    fun recent(): List<String> = entries.sortedByDescending { it.last }.map { it.path }

    fun usage(): Map<String, Int> = entries.associate { it.path to it.count }

    /** The log after using [paths] now, keeping only the [MAX_ENTRIES] most recent folders. */
    fun recorded(paths: List<String>): RecentLog {
        var clock = entries.maxOfOrNull { it.last } ?: 0L
        val byPath = entries.associateByTo(LinkedHashMap()) { it.path }
        paths.forEach { path ->
            clock++
            byPath[path] = Entry(path, (byPath[path]?.count ?: 0) + 1, clock)
        }
        val kept = byPath.values.sortedByDescending { it.last }.take(MAX_ENTRIES)
        return RecentLog(kept)
    }

    /** One line per folder: count, time and path, tab separated and escaped. */
    fun encode(): String = entries.joinToString("\n") {
        "${it.count}\t${it.last}\t${escape(it.path)}"
    }

    companion object {
        const val MAX_ENTRIES = 100

        val Empty = RecentLog(emptyList())

        /** Reads what [encode] wrote; damaged lines are skipped rather than failing. */
        fun decode(text: String?): RecentLog {
            if (text.isNullOrEmpty()) return Empty
            val entries = text.split('\n').mapNotNull { line ->
                val parts = line.split('\t', limit = FIELDS)
                val count = parts.getOrNull(0)?.toIntOrNull()
                val last = parts.getOrNull(1)?.toLongOrNull()
                val path = parts.getOrNull(2)?.let(::unescape)
                if (count == null || last == null || path.isNullOrEmpty()) {
                    null
                } else {
                    Entry(path, count, last)
                }
            }
            return RecentLog(entries.distinctBy { it.path })
        }

        private const val FIELDS = 3

        private fun escape(path: String) = path.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")

        private fun unescape(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\\' && index + 1 < text.length) {
                    index++
                    out.append(
                        when (text[index]) {
                            't' -> '\t'
                            'n' -> '\n'
                            else -> text[index]
                        }
                    )
                } else {
                    out.append(char)
                }
                index++
            }
            return out.toString()
        }
    }
}
