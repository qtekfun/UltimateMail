// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** Decides which requests a message's WebView may make, and which links may be opened. */
object RequestPolicy {
    /**
     * Inline images (`data:`) and message parts (`cid:`) always load; `https:` only when the
     * reader allowed remote content. Everything else (http, file, content, javascript...) never.
     */
    fun allowsRequest(url: String, allowRemoteContent: Boolean): Boolean =
        when (UrlPolicy.kind(UrlPolicy.clean(url))) {
            UrlKind.DATA_IMAGE, UrlKind.CID -> true
            UrlKind.HTTPS -> allowRemoteContent
            else -> false
        }

    /**
     * Whether [url] is the message document itself being loaded into the view: the page is
     * handed to the WebView as a `data:text/html` URL, and that main-frame request must pass or
     * nothing renders at all. Only the first load of that exact kind counts; any other main-frame
     * navigation stays blocked.
     */
    fun isOwnDocument(url: String, isMainFrame: Boolean): Boolean =
        isMainFrame && UrlPolicy.clean(url).startsWith("data:text/html", ignoreCase = true)

    /**
     * The URL to hand to an `ACTION_VIEW` intent for a tapped link, or null when the link must
     * not be opened at all. Only web, mail and phone links qualify.
     */
    fun externalLink(url: String): String? {
        val cleaned = UrlPolicy.clean(url)
        val kind = UrlPolicy.kind(cleaned)
        val openable = kind == UrlKind.HTTPS || kind == UrlKind.HTTP ||
            kind == UrlKind.MAILTO || kind == UrlKind.TEL
        return cleaned.takeIf { openable }
    }
}
