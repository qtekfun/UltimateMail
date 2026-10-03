// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Turns what the user typed into a [SearchQuery]. The language is Gmail's, reduced to what mail
 * kept in Room can answer:
 *
 * - free words, all of which must be found; the word being typed (no space after it yet) also
 *   matches as a prefix;
 * - `"quoted phrases"`, and `-word` / `-"phrase"` to exclude;
 * - `from:` `to:` `subject:` `label:` (or `in:`), with a quoted value for text with spaces;
 * - `has:attachment`, `is:unread`, `is:read`, `is:starred`;
 * - `after:` and `before:` with `2026-03-31` or `2026/03/31`.
 *
 * Parsing never fails. Whatever is not understood (an unknown operator, a date that does not
 * exist, `is:banana`) is searched for as plain words, and an operator with no value yet (the user
 * is still typing `from:`) is ignored. `OR`, `NOT` and `NEAR` have no special meaning: they are
 * ordinary words, which keeps what is typed from ever reaching the search engine as syntax. A
 * capital `AND` is dropped, since words are already combined with AND. Negation applies to words
 * and phrases only; `-from:x` is ignored rather than turned into the opposite of what was meant.
 */
object SearchQueryParser {
    fun parse(input: String): SearchQuery {
        val tokens = Tokenizer(input).tokens()
        val builder = Builder()
        val typing = input.isNotEmpty() && !input.last().isWhitespace()
        tokens.forEachIndexed { index, token ->
            builder.add(token, beingTyped = typing && index == tokens.lastIndex)
        }
        return builder.build()
    }

    /** The date of `2026-03-31` or `2026/3/31`; null for anything else, a missing day included. */
    fun date(text: String): LocalDate? {
        val match = DATE.matchEntire(text.trim()) ?: return null
        val (year, month, day) = match.destructured
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    private enum class Kind {
        /** Text up to a blank; it may be `name:value`. */
        WORD,

        /** Text between quotes, quotes excluded. */
        PHRASE,

        /** `name:"quoted value"`: the text is `name:` followed by the value without quotes. */
        QUOTED_VALUE
    }

    private data class Token(val text: String, val kind: Kind, val negated: Boolean)

    private class Tokenizer(private val input: String) {
        private var position = 0

        fun tokens(): List<Token> = buildList {
            while (true) {
                skipBlanks()
                if (position >= input.length) break
                add(next())
            }
        }

        private fun skipBlanks() {
            while (position < input.length && input[position].isWhitespace()) position++
        }

        private fun next(): Token {
            val negated = input[position] == '-' && position + 1 < input.length &&
                !input[position + 1].isWhitespace()
            if (negated) position++
            if (input[position] == '"') {
                position++
                return Token(quotedText(), Kind.PHRASE, negated)
            }
            return word(negated)
        }

        /** Text up to the closing quote, or to the end while the user has not closed it yet. */
        private fun quotedText(): String {
            val end = input.indexOf('"', position).let { if (it < 0) input.length else it }
            val text = input.substring(position, end)
            position = minOf(end + 1, input.length)
            return text
        }

        /** Reads up to a blank; a quote right after `name:` opens the quoted value of it. */
        private fun word(negated: Boolean): Token {
            val text = StringBuilder()
            var kind = Kind.WORD
            var done = false
            while (!done && position < input.length) {
                val char = input[position]
                when {
                    char.isWhitespace() -> done = true

                    char == '"' -> {
                        // A quote inside a word ends it; the text after starts the next token.
                        done = true
                        if (text.endsWith(':')) {
                            position++
                            text.append(quotedText())
                            kind = Kind.QUOTED_VALUE
                        }
                    }

                    else -> {
                        text.append(char)
                        position++
                    }
                }
            }
            return Token(text.toString(), kind, negated)
        }
    }

    private class Operator(val name: String, val value: String, val quoted: Boolean)

    private fun operatorOf(token: Token): Operator? {
        val colon = token.text.indexOf(':')
        val name = token.text.substring(0, maxOf(colon, 0)).lowercase(Locale.ROOT)
        return if (token.kind == Kind.PHRASE || name !in OPERATORS) {
            null
        } else {
            Operator(name, token.text.substring(colon + 1), token.kind == Kind.QUOTED_VALUE)
        }
    }

    private class Builder {
        private val terms = mutableListOf<SearchTerm>()
        private val excluded = mutableListOf<SearchTerm>()
        private val from = mutableListOf<SearchTerm>()
        private val to = mutableListOf<SearchTerm>()
        private val subject = mutableListOf<SearchTerm>()
        private val labels = mutableListOf<String>()
        private var hasAttachment = false
        private var unread: Boolean? = null
        private var starred = false
        private var after: LocalDate? = null
        private var before: LocalDate? = null

        fun add(token: Token, beingTyped: Boolean) {
            val operator = operatorOf(token)
            when {
                operator != null -> addOperator(operator, token, beingTyped)
                token.negated -> addText(excluded, token.text, token.kind == Kind.PHRASE, false)
                else -> addFree(token, beingTyped)
            }
        }

        private fun addFree(token: Token, beingTyped: Boolean) {
            val phrase = token.kind == Kind.PHRASE
            if (!phrase && token.text == AND) return
            addText(terms, token.text, phrase, prefix = beingTyped && !phrase)
        }

        private fun addOperator(operator: Operator, token: Token, beingTyped: Boolean) {
            if (token.negated || operator.value.isBlank()) return
            val value = operator.value.trim()
            // Not understood after all: search for what the user wrote, as text.
            if (!applyOperator(operator, value, beingTyped)) addFree(token, beingTyped)
        }

        /** Applies a known operator; false when its [value] does not fit it. */
        private fun applyOperator(operator: Operator, value: String, beingTyped: Boolean): Boolean =
            when (operator.name) {
                "from", "to", "subject" -> true.also {
                    addTextOperator(operator, value, beingTyped)
                }

                "label", "in" -> labels.add(value)

                "has" -> (value.lowercase(Locale.ROOT) in HAS_VALUES).also {
                    if (it) hasAttachment = true
                }

                "is" -> isFlag(value)

                else -> dateOperator(operator.name, value)
            }

        private fun addTextOperator(operator: Operator, value: String, beingTyped: Boolean) {
            val quoted = operator.quoted
            when (operator.name) {
                "from" -> addText(from, value, quoted, prefix = !quoted)
                "to" -> addText(to, value, quoted, prefix = !quoted)
                else -> addText(subject, value, quoted, prefix = beingTyped && !quoted)
            }
        }

        private fun dateOperator(name: String, value: String): Boolean {
            val date = SearchQueryParser.date(value) ?: return false
            if (name == "after") after = date else before = date
            return true
        }

        private fun isFlag(value: String): Boolean = when (value.lowercase(Locale.ROOT)) {
            "unread" -> true.also { unread = true }
            "read" -> true.also { unread = false }
            "starred", "flagged" -> true.also { starred = true }
            else -> false
        }

        private fun addText(
            into: MutableList<SearchTerm>,
            text: String,
            phrase: Boolean,
            prefix: Boolean
        ) {
            val clean = text.trim()
            // Nothing the index could ever hold ("!!", "-"): searching for it would find nothing.
            if (clean.none(Char::isLetterOrDigit)) return
            // A quoted single word is a plain word; only text with blanks is a phrase.
            val isPhrase = phrase && clean.any(Char::isWhitespace)
            into.add(SearchTerm(clean, isPhrase, prefix && !isPhrase))
        }

        fun build() = SearchQuery(
            terms = terms.toList(),
            excluded = excluded.toList(),
            from = from.toList(),
            to = to.toList(),
            subject = subject.toList(),
            labels = labels.toList(),
            hasAttachment = hasAttachment,
            unread = unread,
            starred = starred,
            after = after,
            before = before
        )
    }

    private const val AND = "AND"
    private val DATE = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$")
    private val HAS_VALUES = setOf("attachment", "attachments")
    private val OPERATORS =
        setOf("from", "to", "subject", "label", "in", "has", "is", "before", "after")
}
