// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/**
 * The result of sanitizing an e-mail's HTML.
 *
 * @property html a fragment safe to place in a document body: only allow-listed tags and
 *   attributes, no scripts, no remote references that would load.
 * @property blockedRemoteCount remote references (images, CSS `url()`) that were kept out; a
 *   remote image is still in [html] but its address sits in `data-blocked-src`.
 * @property links the links kept in [html], in document order.
 */
data class SanitizedHtml(val html: String, val blockedRemoteCount: Int, val links: List<HtmlLink>) {
    /** True when remote content was blocked, so the UI can offer to load it for this message. */
    val hadBlockedRemoteContent: Boolean get() = blockedRemoteCount > 0

    /**
     * True when some link to [url] shows an address that is not where it goes, so the reader
     * must confirm the destination. [url] may differ from the written href by a trailing slash,
     * which is how a WebView reports it.
     */
    fun isDeceptiveTarget(url: String): Boolean =
        links.any { it.deceptive && it.href.trimEnd('/') == url.trimEnd('/') }
}
