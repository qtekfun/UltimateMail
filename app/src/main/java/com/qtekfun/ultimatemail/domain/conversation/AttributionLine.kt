// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/**
 * Recognises the line mail clients put above a quoted reply ("On Mon, 1 Jan 2024, Ana wrote:")
 * in the languages the app is likely to meet. The line may be wrapped over a few lines.
 */
internal object AttributionLine {
    /** Lines an attribution may be wrapped over. */
    const val MAX_LINES = 3

    private val pattern = Regex(
        "^\\W{0,3}(?:on|el|le|am|il|em|op|den|på)\\s.{2,300}?" +
            "(?<!\\p{L})(?:wrote|escribi(?:ó|o|&oacute;)|a\\s+(?:é|e|&eacute;)crit|schrieb|" +
            "ha\\s+scritto|escreveu|schreef|skrev)(?!\\p{L}).{0,200}?:\\W{0,3}$",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when [text] is such a line. A date or an address must be in it, so that an ordinary
     * sentence that happens to start with "El" and end with a colon is not taken for one.
     */
    fun matches(text: String): Boolean {
        val line = text.trim()
        return (line.any { it.isDigit() } || '@' in line) && pattern.matches(line)
    }
}
