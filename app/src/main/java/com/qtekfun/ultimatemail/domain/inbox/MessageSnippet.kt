// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.domain.compose.HtmlText

/**
 * The few words of a message the list shows under the subject (the preview): the start of the
 * text, on one line, without quoted lines. It is kept with the message when its body is stored.
 */
object MessageSnippet {
    /** Enough for five lines of preview on a wide screen; the row cuts it to the user's lines. */
    const val MAX_LENGTH = 300

    private val whitespace = Regex("\\s+")

    fun of(text: String?, html: String?): String {
        val plain = text ?: html?.let(HtmlText::toPlain) ?: return ""
        return plain.lineSequence()
            .filterNot { it.trimStart().startsWith(">") }
            .joinToString(" ")
            .replace(whitespace, " ")
            .trim()
            .take(MAX_LENGTH)
    }
}
