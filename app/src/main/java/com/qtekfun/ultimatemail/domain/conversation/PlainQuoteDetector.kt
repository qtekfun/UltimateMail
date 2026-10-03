// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/**
 * Finds the quoted earlier mail at the end of a plain-text message (RF-04): the reply sits on
 * top and the quote below it, starting at the attribution line ("On ... wrote:"), at an Outlook
 * separator or header block, or at the first line starting with `>`.
 *
 * Only a quote that runs to the end of the message is folded away. Answers written between the
 * quoted lines (interleaved replies) and text after the quote are content, so nothing is folded
 * then. A forwarded message is content as well, and a message that is nothing but a quote is
 * shown in full.
 */
object PlainQuoteDetector {
    private val originalMessage = Regex(
        "^\\W*-{2,}\\s*(?:original message|mensaje original|message d'origine|" +
            "ursprüngliche nachricht|messaggio originale|mensagem original)\\s*-{2,}\\W*$",
        RegexOption.IGNORE_CASE
    )

    fun split(text: String): QuoteSplit {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val start = quoteStart(lines)
        val visible = start?.let { lines.subList(0, it).joinToString("\n").trimEnd() }
        return if (start == null || visible.isNullOrBlank()) {
            QuoteSplit.whole(text)
        } else {
            QuoteSplit(visible, lines.subList(start, lines.size).joinToString("\n").trimEnd())
        }
    }

    private fun quoteStart(lines: List<String>): Int? =
        lines.indices.firstNotNullOfOrNull { candidateAt(lines, it) }

    /** The line a quote starts at when one starts at [index], or null. */
    private fun candidateAt(lines: List<String>, index: Int): Int? {
        val line = lines[index]
        val attributionEnd = attributionEnd(lines, index)
        return when {
            attributionEnd != null -> index.takeIf { attributionIsQuote(lines, attributionEnd) }

            originalMessage.matches(line.trim()) -> index

            QuoteHeaders.isHeaderBlock(lines, index) ->
                QuoteHeaders.headerStart(lines, index)

            isQuoted(line) && restIsQuote(lines, index) -> index

            else -> null
        }
    }

    /** An attribution is followed by something, and by nothing but quote if that is `>` text. */
    private fun attributionIsQuote(lines: List<String>, end: Int): Boolean {
        val next = nextNonBlank(lines, end + 1)
        return next != null && (!isQuoted(lines[next]) || restIsQuote(lines, next))
    }

    private fun attributionEnd(lines: List<String>, index: Int): Int? {
        val last = minOf(lines.size, index + AttributionLine.MAX_LINES)
        // A blank line ends the search: an attribution is never wrapped across one.
        val reach = (index until last).firstOrNull { lines[it].isBlank() } ?: last
        return (index until reach).firstOrNull { end ->
            AttributionLine.matches(lines.subList(index, end + 1).joinToString(" "))
        }
    }

    private fun isQuoted(line: String) = line.trimStart().startsWith(">")

    private fun restIsQuote(lines: List<String>, from: Int) =
        (from until lines.size).all { lines[it].isBlank() || isQuoted(lines[it]) }

    private fun nextNonBlank(lines: List<String>, from: Int): Int? =
        (from until lines.size).firstOrNull { lines[it].isNotBlank() }
}
