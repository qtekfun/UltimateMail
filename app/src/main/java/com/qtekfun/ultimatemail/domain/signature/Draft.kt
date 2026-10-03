// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.signature

/** One line of a body: its text and the line ending that followed it ("" for the last line). */
internal data class Line(val text: String, val eol: String)

/** A draft body as lines, with just enough structure to find the signature and the quote. */
internal class Draft private constructor(private val lines: List<Line>) {
    /** The line ending used for lines we add: the one the body already uses. */
    private val eol: String = lines.map { it.eol }.firstOrNull { it.isNotEmpty() } ?: "\n"

    private val firstQuoted = lines.indexOfFirst { it.text.startsWith(">") }
    private val forwardHeader = lines.indexOfFirst { FORWARD_HEADER.matches(it.text.trim()) }
    private val lastQuoted = lines.indexOfLast { it.text.startsWith(">") }
    private val quoteStart: Int
    private val foreignQuote: Boolean

    init {
        val start = listOf(firstQuoted, forwardHeader).filter { it >= 0 }.minOrNull() ?: lines.size
        // "On <date>, <name> wrote:" belongs to the quote.
        val hasAttribution = firstQuoted >= 0 && start == firstQuoted && start > 0 &&
            lines[start - 1].text.trimEnd().endsWith(":")
        quoteStart = if (hasAttribution) start - 1 else start
        foreignQuote = forwardHeader >= 0 && start == forwardHeader
    }

    fun render(): String = lines.joinToString("") { it.text + it.eol }

    /**
     * Where the signature block is: the range of lines. [expected] (the block of a known
     * signature) is looked for by its exact text anywhere; otherwise, or if it is not there, the
     * last delimiter outside the quote is taken, running to the quote or the end of the body.
     */
    fun locate(expected: List<String>?): IntRange? =
        expected?.let(::locateExact) ?: locateByDelimiter()

    private fun locateExact(expected: List<String>): IntRange? =
        (lines.size - expected.size downTo 0)
            .firstOrNull { start -> expected.indices.all { matches(start + it, expected[it]) } }
            ?.let { it until it + expected.size }

    private fun matches(index: Int, expected: String): Boolean {
        val text = lines[index].text
        return if (expected ==
            SignatureEditor.DELIMITER
        ) {
            DELIMITER_LINE.matches(text)
        } else {
            text.trimEnd() == expected
        }
    }

    private fun locateByDelimiter(): IntRange? {
        val start =
            lines.indices.lastOrNull {
                DELIMITER_LINE.matches(lines[it].text) && isOutsideQuote(it)
            }
                ?: return null
        var end = if (start < quoteStart) quoteStart else lines.size
        while (end > start + 1 && lines[end - 1].text.isBlank()) end--
        return start until end
    }

    private fun isOutsideQuote(index: Int) =
        index < quoteStart || (!foreignQuote && index > lastQuoted)

    /** Replaces the lines of the block at [range] by [block], in the same place. */
    fun swap(range: IntRange, block: List<String>): Draft {
        val closing = lines[range.last].eol
        val added = block.mapIndexed { i, text ->
            Line(
                text,
                if (i ==
                    block.lastIndex
                ) {
                    closing
                } else {
                    eol
                }
            )
        }
        return Draft(
            lines.subList(0, range.first) + added + lines.subList(range.last + 1, lines.size)
        )
    }

    /**
     * Removes the block at [range] together with one blank line next to it, the one that
     * separated it from the text around it.
     */
    fun remove(range: IntRange): Draft {
        val before = lines.subList(0, range.first)
        val after = lines.subList(range.last + 1, lines.size)
        val closing = lines[range.last].eol
        val kept = when {
            after.isNotEmpty() && after.first().text.isBlank() -> before + after.drop(1)
            before.isNotEmpty() && before.last().text.isBlank() -> before.dropLast(1) + after
            else -> before + after
        }
        // A block that ended the body leaves the text before it ending the body, too.
        val endsBody = range.last == lines.lastIndex
        return Draft(
            if (endsBody &&
                kept.isNotEmpty()
            ) {
                kept.dropLast(1) + kept.last().copy(eol = closing)
            } else {
                kept
            }
        )
    }

    /** Adds [block] above the quote (replies and forwards that want it there) or at the end. */
    fun insert(block: List<String>, kind: ComposeKind, beforeQuote: Boolean): Draft {
        val above = kind != ComposeKind.NEW && beforeQuote && quoteStart < lines.size
        val head = (if (above) lines.subList(0, quoteStart) else lines).terminated()
        val separator = if (head.isEmpty() ||
            head.last().text.isNotBlank()
        ) {
            listOf(Line("", eol))
        } else {
            emptyList()
        }
        val lastEol = if (above) eol else ""
        val added = block.mapIndexed { i, text ->
            Line(
                text,
                if (i ==
                    block.lastIndex
                ) {
                    lastEol
                } else {
                    eol
                }
            )
        }
        val tail = if (above) {
            listOf(
                Line("", eol)
            ) + lines.subList(quoteStart, lines.size)
        } else {
            emptyList()
        }
        return Draft(head + separator + added + tail)
    }

    /** The same lines, with a line ending after the last one so that more can follow. */
    private fun List<Line>.terminated(): List<Line> =
        if (isNotEmpty() && last().eol.isEmpty()) dropLast(1) + last().copy(eol = eol) else this

    companion object {
        private val DELIMITER_LINE = Regex("""--[ \t\r]*""")
        private val FORWARD_HEADER = Regex(
            """-{2,}\s*(Forwarded message|Original Message|Mensaje reenviado|Mensaje original)\s*-{2,}""",
            RegexOption.IGNORE_CASE
        )

        fun parse(body: String): Draft {
            val lines = mutableListOf<Line>()
            var start = 0
            while (start < body.length) {
                val newline = body.indexOf('\n', start)
                if (newline < 0) {
                    lines += Line(body.substring(start), "")
                    break
                }
                val crlf = newline > start && body[newline - 1] == '\r'
                lines +=
                    Line(
                        body.substring(start, if (crlf) newline - 1 else newline),
                        if (crlf) "\r\n" else "\n"
                    )
                start = newline + 1
            }
            return Draft(lines)
        }
    }
}
