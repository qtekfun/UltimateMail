// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import java.util.Locale

/**
 * The full-text part of a search, as FTS4 MATCH expressions for `message_fts`.
 *
 * A message is a hit when it satisfies *any* of [matches] (they differ only in which column
 * `from:` is looked for in: the sender's name or address) and none of [excludes]. With no
 * [matches] the full-text index is not needed to find candidates.
 */
data class FtsPlan(val matches: List<String>, val excludes: List<String>) {
    val isEmpty: Boolean get() = matches.isEmpty() && excludes.isEmpty()
}

/**
 * Translates a [SearchQuery] into MATCH expressions that cannot be anything but what the user
 * meant. Every word and phrase is emitted as a quoted phrase (for a column filter, as lower-case
 * words made of letters and digits only, which cannot be anything else), so the FTS operators
 * (`OR`, `NOT`, `NEAR`, `-`, `*`, `^`, parentheses, column filters) lose their meaning whatever
 * is typed, and
 * the only syntax that is added is the column filter and the prefix star written here. Quotes,
 * stars and control characters (a NUL would end the SQL string) in the text turn into blanks,
 * and text with nothing searchable in it is dropped.
 */
object FtsQueryBuilder {
    private const val SUBJECT = "subject"
    private const val SENDER_NAME = "senderName"
    private const val SENDER_ADDRESS = "senderAddress"
    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

    fun plan(query: SearchQuery): FtsPlan {
        val base = query.terms.mapNotNull { phrase(it, null) } +
            query.subject.mapNotNull { phrase(it, SUBJECT) }
        val matches = if (query.from.isEmpty()) {
            listOf(base)
        } else {
            listOf(SENDER_NAME, SENDER_ADDRESS).map { column ->
                base + query.from.mapNotNull { phrase(it, column) }
            }
        }
        return FtsPlan(
            matches = matches.filter { it.isNotEmpty() }.map { it.joinToString(" ") },
            excludes = query.excluded.mapNotNull { phrase(it, null) }
        )
    }

    /**
     * One term as `"text"` or `"text*"`, or, for a [column], one `column:word` per word (FTS4
     * applies a column filter to a bare word, not to a quoted phrase); null when nothing
     * searchable is left of it. The words of a column filter are written in lower case so that
     * none can read as an operator (`OR`, `NOT`, `NEAR`); the tokenizer folds case anyway.
     */
    internal fun phrase(term: SearchTerm, column: String?): String? {
        val clean = sanitize(term.text)
        return if (column != null) {
            columnWords(clean, term.prefix, column)
        } else {
            quoted(clean, term.prefix)
        }
    }

    private fun quoted(clean: String, prefix: Boolean): String? {
        val text = if (prefix) clean.trimEnd { !it.isLetterOrDigit() } else clean
        return when {
            text.none(Char::isLetterOrDigit) -> null
            prefix -> "\"$text*\""
            else -> "\"$text\""
        }
    }

    private fun columnWords(text: String, prefix: Boolean, column: String): String? {
        val words = wordsOf(text)
        if (words.isEmpty()) return null
        return words.mapIndexed { index, word ->
            val star = if (prefix && index == words.lastIndex) "*" else ""
            "$column:${word.lowercase(Locale.ROOT)}$star"
        }.joinToString(" ")
    }

    /** The runs of letters, digits and combining marks: what the tokenizer makes words of. */
    private fun wordsOf(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isWordPart(codePoint)) {
                current.appendCodePoint(codePoint)
            } else if (current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private fun isWordPart(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || Character.getType(codePoint).toByte() in MARKS

    private fun isSeparator(char: Char) =
        char.isWhitespace() || char.isISOControl() || char == '"' || char == '*'

    private fun sanitize(text: String): String {
        val out = StringBuilder(text.length)
        var blank = false
        for (char in text) {
            if (isSeparator(char)) {
                blank = out.isNotEmpty()
            } else {
                if (blank) out.append(' ')
                blank = false
                out.append(char)
            }
        }
        return out.toString()
    }
}
