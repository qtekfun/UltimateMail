// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import java.net.URLDecoder

/**
 * Content-IDs tie a `cid:` image in the HTML to the part of the message that holds it. The
 * header has them as `<id@host>`, the HTML as `cid:id@host` (sometimes percent-encoded or in
 * another case), so both are reduced to the same form before they are compared.
 */
object ContentIds {
    /** The id without angle brackets, `cid:` prefix, percent-encoding or case; null if empty. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        val hasPrefix = text.startsWith(PREFIX, ignoreCase = true)
        val trimmed = if (hasPrefix) text.drop(PREFIX.length).trim() else text
        val decoded = runCatching { URLDecoder.decode(trimmed.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(trimmed)
        return decoded.trim().trim('<', '>').trim().lowercase().takeIf { it.isNotEmpty() }
    }

    private const val PREFIX = "cid:"

    private val reference = Regex("cid:([^\\s\"'<>)]+)", RegexOption.IGNORE_CASE)

    /** The normalized ids the HTML refers to as `cid:`. */
    fun referencedIn(html: String): Set<String> =
        reference.findAll(html).mapNotNull { normalize(it.groupValues[1]) }.toSet()
}
