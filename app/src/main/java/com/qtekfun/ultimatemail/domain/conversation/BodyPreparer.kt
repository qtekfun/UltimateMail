// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.domain.html.HtmlSanitizer
import com.qtekfun.ultimatemail.domain.html.SanitizedHtml

/** A body ready to draw: sanitized HTML for the web view, or plain text with its links found. */
sealed interface RenderedBody {
    data class Html(val sanitized: SanitizedHtml) : RenderedBody

    data class Text(val runs: List<TextRun>) : RenderedBody

    /** The message has no text at all. */
    data object Empty : RenderedBody
}

/**
 * A message body in the two shapes the reader can ask for: [collapsed] without the quoted
 * earlier mail, and [expanded] with it (null when the message quotes nothing).
 */
data class PreparedBody(val collapsed: RenderedBody, val expanded: RenderedBody?) {
    val hasQuote: Boolean get() = expanded != null

    fun shown(showQuoted: Boolean): RenderedBody =
        if (showQuoted && expanded != null) expanded else collapsed

    /** Remote content was kept out of the message (or of its quote). */
    val hadBlockedRemoteContent: Boolean
        get() = ((expanded ?: collapsed) as? RenderedBody.Html)?.sanitized
            ?.hadBlockedRemoteContent == true
}

/**
 * Turns the stored body of a message into what the screen draws (RF-04): HTML when there is
 * some, sanitized (remote content only if [prepare] is told to allow it), otherwise the text.
 * The quoted earlier mail is cut off with [HtmlQuoteDetector] or [PlainQuoteDetector]. Results
 * are kept for the last few messages, because sanitizing a large newsletter is not free and the
 * screen asks again whenever anything about the conversation changes.
 */
class BodyPreparer(private val sanitizer: HtmlSanitizer = HtmlSanitizer()) {
    private data class Key(
        val messageId: Long,
        val allowRemote: Boolean,
        val textLength: Int?,
        val htmlLength: Int?
    )

    private val cache = object : LinkedHashMap<Key, PreparedBody>(CACHE_SIZE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PreparedBody>) =
            size > CACHE_SIZE
    }

    fun prepare(messageId: Long, text: String?, html: String?, allowRemote: Boolean): PreparedBody {
        val key = Key(messageId, allowRemote, text?.length, html?.length)
        return synchronized(cache) { cache[key] } ?: build(text, html, allowRemote).also {
            synchronized(cache) { cache[key] = it }
        }
    }

    private fun build(text: String?, html: String?, allowRemote: Boolean): PreparedBody = when {
        !html.isNullOrBlank() -> fromHtml(html, allowRemote)
        !text.isNullOrBlank() -> fromText(text)
        else -> PreparedBody(RenderedBody.Empty, null)
    }

    private fun fromHtml(html: String, allowRemote: Boolean): PreparedBody {
        val split = HtmlQuoteDetector.split(html)
        val full = RenderedBody.Html(sanitizer.sanitize(html, allowRemote))
        return if (split.hasQuote) {
            PreparedBody(RenderedBody.Html(sanitizer.sanitize(split.visible, allowRemote)), full)
        } else {
            PreparedBody(full, null)
        }
    }

    private fun fromText(text: String): PreparedBody {
        val split = PlainQuoteDetector.split(text)
        val full = RenderedBody.Text(LinkDetector.runs(text))
        return if (split.hasQuote) {
            PreparedBody(RenderedBody.Text(LinkDetector.runs(split.visible)), full)
        } else {
            PreparedBody(full, null)
        }
    }

    private companion object {
        const val CACHE_SIZE = 16
        const val LOAD_FACTOR = 0.75f
    }
}
