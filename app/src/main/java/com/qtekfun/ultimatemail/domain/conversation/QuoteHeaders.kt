// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/**
 * Recognises the header block Outlook and webmail put above a quoted reply ("From: ... Sent: ...
 * To: ... Subject: ...") in plain text, and the markers that introduce a forwarded message, which
 * are content and not a quote.
 */
internal object QuoteHeaders {
    private val forwardMarkers = listOf(
        "forwarded message",
        "mensaje reenviado",
        "message transféré",
        "message transfere",
        "weitergeleitete nachricht",
        "messaggio inoltrato",
        "mensagem encaminhada"
    )

    private val underline = Regex("^\\s*[_-]{10,}\\s*$")
    private val fromHeader = Regex("^\\W{0,2}(?:from|de|von|da|van)\\s*:", RegexOption.IGNORE_CASE)
    private val otherHeader = Regex(
        "^\\W{0,2}(?:sent|date|enviado(?: el)?|fecha|gesendet|datum|to|para|an|subject|asunto|" +
            "betreff|cc)\\s*:",
        RegexOption.IGNORE_CASE
    )

    /** Header lines looked at after a "From:" line. */
    private const val HEADER_WINDOW = 8

    /** Header lines (besides "From:") a block needs to be taken for the header of a quote. */
    private const val MIN_OTHER_HEADERS = 2

    /** Longest line still taken for a forward marker; longer ones are sentences. */
    private const val MAX_MARKER_LENGTH = 80

    /** A "From:" line followed by more header lines, and not part of a forwarded message. */
    fun isHeaderBlock(lines: List<String>, index: Int): Boolean {
        if (!fromHeader.containsMatchIn(lines[index])) return false
        val others = lines.subList(index + 1, minOf(lines.size, index + 1 + HEADER_WINDOW))
            .takeWhile { it.isNotBlank() }
            .count { otherHeader.containsMatchIn(it) }
        val before = previousNonBlank(lines, index - 1)
        return others >= MIN_OTHER_HEADERS && (before == null || !isForwardMarker(lines[before]))
    }

    /** Outlook draws a rule above its header block; the quote starts at the rule. */
    fun headerStart(lines: List<String>, index: Int): Int {
        val before = previousNonBlank(lines, index - 1)
        return if (before != null && underline.matches(lines[before])) before else index
    }

    private fun isForwardMarker(line: String): Boolean {
        val lower = line.trim().lowercase()
        val named = lower.length <= MAX_MARKER_LENGTH && forwardMarkers.any { it in lower }
        return named || lower.startsWith("begin forwarded") ||
            lower.startsWith("inicio del mensaje reenviado")
    }

    private fun previousNonBlank(lines: List<String>, from: Int): Int? =
        (from downTo 0).firstOrNull { lines[it].isNotBlank() }
}
