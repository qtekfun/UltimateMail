// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

/**
 * A plain-text reading of an HTML body, for quoting a message that has no text part. It is not
 * a renderer: block tags become line breaks, other tags vanish, and the handful of entities that
 * show up in prose are decoded. Anything it does not understand is left out rather than guessed.
 */
object HtmlText {
    private val dropped = Regex("(?is)<(script|style|head)\\b.*?</\\1\\s*>")
    private val breaks = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6])\\b[^>]*>")
    private val tags = Regex("(?s)<[^>]*>")
    private val blankRuns = Regex("\\n{3,}")
    private val entities = mapOf(
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&amp;" to "&"
    )

    fun toPlain(html: String): String {
        var text = dropped.replace(html, "")
        text = breaks.replace(text, "\n")
        text = tags.replace(text, "")
        // "&amp;" last, so "&amp;lt;" ends up as "&lt;" and not "<".
        entities.forEach { (entity, plain) -> text = text.replace(entity, plain) }
        return blankRuns.replace(text.replace("\r\n", "\n"), "\n\n").trim()
    }
}
