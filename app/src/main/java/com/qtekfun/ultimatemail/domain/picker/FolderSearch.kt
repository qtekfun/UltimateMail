// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

/** How well a query matches a folder; a later value is a better match. */
enum class MatchLevel { SUBSTRING, WORD_START, PREFIX, EXACT }

/**
 * A folder or label to search: [id] identifies it in the result, [name] is the display name,
 * [path] the full path with its hierarchy, [usage] how often the user picked it (a tie-breaker).
 */
data class SearchTarget(val id: String, val name: String, val path: String, val usage: Int = 0)

/**
 * A match. The ranges are inclusive character ranges of the original [SearchTarget.name] and
 * [SearchTarget.path] to highlight.
 */
data class SearchHit(
    val id: String,
    val level: MatchLevel,
    val nameRanges: List<IntRange>,
    val pathRanges: List<IntRange>
)

/**
 * Live filtering of the folder list (RF-06). The text of every target is folded once (case and
 * accents ignored, see [TextFolding]), so each key press only scans plain strings.
 *
 * A query is split into words; a folder matches when every word occurs in its name or in its full
 * path, so "fact" finds "Work/Invoices 2025/Facturas" and "inv fact" finds it too. Results are
 * ranked: the whole query equal to the name or path (exact), then at the start of the name or
 * path (prefix), then every word at the start of a word or path segment (word start), then
 * anywhere (substring). Within a level, folders whose name contains every word come first, then
 * the most used, then the shortest path, then alphabetical.
 */
class FolderSearch(targets: List<SearchTarget>) {
    private class Entry(val target: SearchTarget) {
        val name = TextFolding.fold(target.name)
        val path = TextFolding.fold(target.path)
    }

    private class Occurrence(val start: Int, val atWordStart: Boolean)

    private class Candidate(
        val entry: Entry,
        val level: MatchLevel,
        val allInName: Boolean,
        val hit: SearchHit
    )

    private val entries = targets.map { Entry(it) }

    /** The matching targets, best first; empty when nothing matches or [query] is blank. */
    fun search(query: String): List<SearchHit> {
        val words = TextFolding.foldedString(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        return entries.mapNotNull { match(it, words, whole) }
            .sortedWith(RANKING)
            .map { it.hit }
    }

    private fun match(entry: Entry, words: List<String>, whole: String): Candidate? {
        var allWordStart = true
        var allInName = true
        val nameRanges = mutableListOf<IntRange>()
        val pathRanges = mutableListOf<IntRange>()
        for (word in words) {
            val inName = occurrence(entry.name.text, word)
            val inPath = occurrence(entry.path.text, word)
            if (inName == null && inPath == null) return null
            allInName = allInName && inName != null
            allWordStart =
                allWordStart && (inName?.atWordStart == true || inPath?.atWordStart == true)
            inName?.let { nameRanges += entry.name.originalRange(it.start, it.start + word.length) }
            inPath?.let { pathRanges += entry.path.originalRange(it.start, it.start + word.length) }
        }
        val level = levelOf(entry, whole, allWordStart)
        val hit = SearchHit(entry.target.id, level, merged(nameRanges), merged(pathRanges))
        return Candidate(entry, level, allInName, hit)
    }

    private fun levelOf(entry: Entry, whole: String, allWordStart: Boolean): MatchLevel = when {
        entry.name.text == whole || entry.path.text == whole -> MatchLevel.EXACT
        entry.name.text.startsWith(whole) || entry.path.text.startsWith(whole) -> MatchLevel.PREFIX
        allWordStart -> MatchLevel.WORD_START
        else -> MatchLevel.SUBSTRING
    }

    /** Where [word] is in [text], preferring an occurrence at the start of a word or segment. */
    private fun occurrence(text: String, word: String): Occurrence? {
        var first: Occurrence? = null
        var from = text.indexOf(word)
        while (from >= 0) {
            val atStart = from == 0 || !Character.isLetterOrDigit(text.codePointBefore(from))
            if (atStart) return Occurrence(from, true)
            if (first == null) first = Occurrence(from, false)
            from = text.indexOf(word, from + 1)
        }
        return first
    }

    private fun merged(ranges: List<IntRange>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                result += range
            }
        }
        return result
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        val RANKING: Comparator<Candidate> = compareByDescending<Candidate> { it.level }
            .thenByDescending { it.allInName }
            .thenByDescending { it.entry.target.usage }
            .thenBy { it.entry.target.path.length }
            .thenBy { it.entry.name.text }
            .thenBy { it.entry.target.path }
    }
}
