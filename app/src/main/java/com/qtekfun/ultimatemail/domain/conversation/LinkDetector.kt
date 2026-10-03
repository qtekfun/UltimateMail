// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.domain.html.RequestPolicy

/** A piece of a plain-text message; [target] is where it leads when it is a link. */
data class TextRun(val text: String, val target: String? = null)

/**
 * Finds the links in a plain-text message (RF-04): web addresses (`http(s)://...`, `www....`) and
 * e-mail addresses. A tap on a link is never followed directly: the target goes through the same
 * confirmation as the links of an HTML message, and only addresses [RequestPolicy] accepts
 * become links at all.
 */
object LinkDetector {
    private val candidate = Regex(
        "(?:https?://|www\\.)[^\\s<>\"]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*" +
            "\\.[A-Za-z]{2,}",
        RegexOption.IGNORE_CASE
    )
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"*>"
    private val brackets = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** [text] cut into runs, in order, that together are exactly [text]. */
    fun runs(text: String): List<TextRun> {
        val runs = ArrayList<TextRun>()
        var position = 0
        for (match in candidate.findAll(text)) {
            val linkText = trimmed(match.value)
            val target = targetOf(linkText)
            if (target != null) {
                if (match.range.first > position) {
                    runs += TextRun(text.substring(position, match.range.first))
                }
                runs += TextRun(linkText, target)
                position = match.range.first + linkText.length
            }
        }
        if (position < text.length) runs += TextRun(text.substring(position))
        return runs
    }

    private fun targetOf(linkText: String): String? {
        val absolute = when {
            linkText.startsWith("www.", ignoreCase = true) -> "https://$linkText"
            "://" in linkText -> linkText
            else -> "mailto:$linkText"
        }
        return RequestPolicy.externalLink(absolute)
    }

    /** Drops what ends a sentence, or closes a bracket the address never opened. */
    private fun trimmed(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val last = raw[end - 1]
            val open = brackets[last]
            val head = raw.substring(0, end)
            val unbalanced = open != null && head.count { it == last } > head.count { it == open }
            if (last in TRAILING_PUNCTUATION || unbalanced) end-- else break
        }
        return raw.substring(0, end)
    }
}
