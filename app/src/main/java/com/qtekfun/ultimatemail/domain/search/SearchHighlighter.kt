// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.domain.picker.TextFolding

/**
 * Finds where the words of a search occur in a text, so the result row can show them in bold.
 * Matching is case- and accent-insensitive like the index ("Ñandú" is found by "nandu") and
 * happens at the start of a word, like the prefix match of the word being typed. The ranges are
 * in the original text (UTF-16 indices), never splitting an emoji or a character with accents.
 */
class SearchHighlighter(terms: List<SearchTerm>) {
    private val needles: List<String> = terms
        .map { TextFolding.foldedString(it.text).trim().replace(BLANKS, " ") }
        .filter { it.any(Char::isLetterOrDigit) }
        .distinct()

    val isEmpty: Boolean get() = needles.isEmpty()

    /** The highlighted ranges of [text], in order, overlapping ones merged. */
    fun ranges(text: String): List<IntRange> {
        if (needles.isEmpty() || text.isEmpty()) return emptyList()
        val folded = TextFolding.fold(text)
        val found = mutableListOf<IntRange>()
        for (needle in needles) {
            var from = folded.text.indexOf(needle)
            while (from >= 0) {
                if (from == 0 || !folded.text[from - 1].isLetterOrDigit()) {
                    found += withMarks(text, folded.originalRange(from, from + needle.length))
                }
                from = folded.text.indexOf(needle, from + 1)
            }
        }
        return merge(found)
    }

    /** Folding drops accents, so the ones written as separate characters must be taken along. */
    private fun withMarks(text: String, range: IntRange): IntRange {
        var last = range.last
        while (last + 1 < text.length && text[last + 1].code in COMBINING_MARKS) last++
        return range.first..last
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    private companion object {
        val BLANKS = Regex("\\s+")
        val COMBINING_MARKS = 0x0300..0x036F
    }
}

/**
 * The line of text shown under a search hit. Full-text search also looks in the cached body, so
 * the stored snippet (the start of the message) may show nothing of what was found: then the
 * line is an excerpt of the body around the first match instead.
 */
object SnippetExcerpt {
    private const val MAX_LENGTH = 140
    private const val LEAD = 40
    private const val SCANNED_BODY = 50_000
    private const val ELLIPSIS = "…"
    private val BLANKS = Regex("\\s+")

    fun of(snippet: String, body: String?, terms: List<SearchTerm>): String {
        val highlighter = SearchHighlighter(terms)
        val scanned = body?.take(SCANNED_BODY).orEmpty()
        val needed = scanned.isNotBlank() && !highlighter.isEmpty &&
            highlighter.ranges(snippet).isEmpty()
        val match = if (needed) highlighter.ranges(scanned).firstOrNull() else null
        if (match == null) return snippet
        return around(scanned, match, truncated = body.orEmpty().length > scanned.length)
    }

    private fun around(scanned: String, match: IntRange, truncated: Boolean): String {
        var start = (match.first - LEAD).coerceAtLeast(0)
        var end = (start + MAX_LENGTH).coerceAtMost(scanned.length)
        // Do not cut an emoji in two.
        if (start > 0 && Character.isLowSurrogate(scanned[start])) start++
        if (end < scanned.length && Character.isHighSurrogate(scanned[end - 1])) end--
        val text = scanned.substring(start, end).replace(BLANKS, " ").trim()
        val head = if (start > 0) ELLIPSIS else ""
        val tail = if (end < scanned.length || truncated) ELLIPSIS else ""
        return head + text + tail
    }
}
