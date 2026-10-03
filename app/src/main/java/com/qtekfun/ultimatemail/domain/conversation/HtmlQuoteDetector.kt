// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.domain.html.HtmlEntities

/**
 * Finds the quoted earlier mail at the end of an HTML message (RF-04). It works on the raw HTML,
 * before sanitizing, because the sanitizer drops the ids some clients mark their quote with; each
 * half is sanitized on its own afterwards, so a wrong cut can only look odd, never be unsafe.
 *
 * A quote starts at the first element a mail client is known to wrap its quote in: Gmail's
 * `gmail_quote`, Yahoo's `yahoo_quoted`, Thunderbird's `moz-cite-prefix`, Outlook's reply header
 * (`divRplyFwdMsg`, `appendonsend`) and, for the rest (Apple Mail, ...), any `blockquote`. The
 * attribution line just above it ("On ... wrote:") goes with it. Like in plain text, only a
 * quote that is the end of the message is folded; text after it (an interleaved reply) means
 * nothing is.
 */
object HtmlQuoteDetector {
    private val tag = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val classAttribute = Regex("class\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val idAttribute = Regex("id\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val blockOpen = Regex("<(?:div|p|span|font)\\b", RegexOption.IGNORE_CASE)
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val styleOrScript = Regex(
        "<(style|script)\\b.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val anyTag = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val image = Regex("<img\\b", RegexOption.IGNORE_CASE)

    /** Classes of elements that hold a whole quote. */
    private val quoteClasses = setOf(
        "gmail_quote",
        "gmail_quote_container",
        "gmail_extra",
        "yahoo_quoted",
        "protonmail_quote"
    )

    /** Classes of an element that only introduces the quote, which follows it. */
    private val introClasses = setOf("moz-cite-prefix", "outlookmessageheader")

    /** Ids of Outlook's reply header; the quote is everything from there on. */
    private val outlookIds = setOf("divrplyfwdmsg", "appendonsend")

    /** How far above a quote its attribution line is looked for. */
    private const val ATTRIBUTION_WINDOW = 1_200

    /** An element where the quote starts; [toEnd] when the quote then runs to the end. */
    private class Marker(val start: Int, val name: String, val afterTag: Int, val toEnd: Boolean)

    fun split(html: String): QuoteSplit {
        val marker = findMarker(html)
        val start = marker?.let { attributionStart(html, it.start) }
        val visible = start?.let { html.substring(0, it) }
        return if (start != null && visible != null && isFoldable(html, marker, visible)) {
            QuoteSplit(visible, html.substring(start))
        } else {
            QuoteSplit.whole(html)
        }
    }

    /**
     * Something is left to read above the quote, and the quote is where the message ends: it runs
     * to the end, or nothing visible follows it.
     */
    private fun isFoldable(html: String, marker: Marker?, visible: String) =
        marker != null && !isBlank(visible) &&
            (marker.toEnd || isBlank(html.substring(end(html, marker))))

    /** The parts of a tag the detector looks at. */
    private class Tag(val closing: Boolean, val name: String, val attributes: String)

    private fun MatchResult.asTag(): Tag {
        val (slash, name, attributes) = destructured
        return Tag(slash.isNotEmpty(), name.lowercase(), attributes)
    }

    private fun findMarker(html: String): Marker? =
        tag.findAll(html).firstNotNullOfOrNull { match ->
            val parsed = match.asTag()
            when {
                parsed.closing -> null
                parsed.name == "blockquote" -> marker(match, parsed.name, toEnd = false)
                else -> classified(match, parsed)
            }
        }

    /** The marker for an element a mail client is known to wrap its quote in, if it is one. */
    private fun classified(match: MatchResult, parsed: Tag): Marker? {
        val classes = classAttribute.find(parsed.attributes)?.groupValues?.get(1)
            ?.lowercase()?.split(spaces).orEmpty().toSet()
        val id = idAttribute.find(parsed.attributes)?.groupValues?.get(1)?.lowercase()
            ?.removePrefix("x_")
        return when {
            id in outlookIds || classes.any { it in introClasses } ->
                marker(match, parsed.name, toEnd = true)

            classes.any { it in quoteClasses } -> marker(match, parsed.name, toEnd = false)

            else -> null
        }
    }

    private fun marker(match: MatchResult, name: String, toEnd: Boolean) =
        Marker(match.range.first, name, match.range.last + 1, toEnd)

    /** Where the element of [marker] closes (the end of the html when it never does). */
    private fun end(html: String, marker: Marker): Int {
        var depth = 1
        for (match in tag.findAll(html, marker.afterTag)) {
            val parsed = match.asTag()
            if (parsed.name != marker.name) continue
            if (parsed.closing) {
                depth--
            } else if (!parsed.attributes.trimEnd().endsWith("/")) {
                depth++
            }
            if (depth == 0) return match.range.last + 1
        }
        return html.length
    }

    /** The quote starts at its attribution line when there is one right above it. */
    private fun attributionStart(html: String, markerStart: Int): Int {
        val from = maxOf(0, markerStart - ATTRIBUTION_WINDOW)
        val window = html.substring(from, markerStart)
        val opening = blockOpen.findAll(window).map { it.range.first }.firstOrNull { offset ->
            AttributionLine.matches(visibleText(window.substring(offset)))
        }
        return if (opening == null) markerStart else from + opening
    }

    private fun isBlank(html: String) = visibleText(html).isEmpty() && !image.containsMatchIn(html)

    /** The text a reader sees: no markup, entities decoded, white space collapsed. */
    private fun visibleText(html: String): String {
        val withoutBlocks = styleOrScript.replace(html, "")
        val text = anyTag.replace(lineBreak.replace(withoutBlocks, " "), "")
        return spaces.replace(HtmlEntities.decode(text), " ").trim()
    }
}
