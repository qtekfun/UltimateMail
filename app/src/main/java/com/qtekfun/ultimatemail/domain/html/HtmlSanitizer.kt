// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/**
 * Allow-list sanitizer for the HTML of received e-mails (SPEC RF-04), written without any
 * HTML library. The input is tokenized tolerantly and the output is written again from scratch,
 * so nothing of the original markup survives unless it is explicitly allowed:
 *
 *  - Only the tags of [ALLOWED_TAGS] are written, with only the attributes of [GLOBAL_ATTRIBUTES]
 *    plus `href` on `a`, `src` on `img`, `background` on table cells and a sanitized `style`.
 *    Event handlers (`onload`, `onerror`...) are therefore never written.
 *  - Scripts, frames, objects, embeds, SVG, MathML, forms, `meta` (refresh), `link` and `base`
 *    are removed; so is the content of the elements that are not markup (script, textarea...).
 *  - URLs are decoded and cleaned before being judged (see [UrlPolicy]). Links keep `http(s)`,
 *    `mailto` and `tel`; images keep `cid:` and raster `data:image`; anything else, including
 *    `javascript:`, is dropped.
 *  - Remote images are not removed: their address moves to `data-blocked-src` so the reader can
 *    allow them for one message. With `allowRemoteContent` only `https` stays loadable.
 *  - CSS goes through [CssSanitizer].
 *
 * The result is a fragment; wrap it with [EmailDocument] to get a page with a strict CSP.
 */
class HtmlSanitizer {
    /** Sanitizes [html]; [allowRemoteContent] lets `https` images and CSS URLs through. */
    fun sanitize(html: String, allowRemoteContent: Boolean = false): SanitizedHtml =
        Run(html, allowRemoteContent).execute()

    private class Run(html: String, private val allowRemote: Boolean) {
        private val tokenizer = HtmlTokenizer(html)
        private val out = StringBuilder(html.length)
        private val open = ArrayList<String>()
        private val links = ArrayList<HtmlLink>()
        private val css = CssSanitizer(allowRemote)
        private var blockedImages = 0
        private var currentLink: LinkBuilder? = null

        fun execute(): SanitizedHtml {
            var token = tokenizer.next()
            while (token != null) {
                when (token) {
                    is HtmlToken.Text -> text(token.raw)
                    is HtmlToken.Start -> start(token)
                    is HtmlToken.End -> end(token.name)
                }
                token = tokenizer.next()
            }
            while (open.isNotEmpty()) close()
            return SanitizedHtml(out.toString(), blockedImages + css.blockedRemote, links)
        }

        private fun text(raw: String) {
            out.append(escapeText(raw))
            currentLink?.append(HtmlEntities.decode(raw))
        }

        private fun start(token: HtmlToken.Start) {
            val name = token.name
            when {
                name == "style" -> {
                    val clean = css.stylesheet(tokenizer.rawText(name))
                    if (clean.isNotEmpty()) out.append("<style>").append(clean).append("</style>")
                }

                name in RAW_TEXT_DROPPED -> tokenizer.rawText(name)

                name == "plaintext" -> tokenizer.skipAll()

                name in DROPPED_ELEMENTS -> tokenizer.skipElement(name)

                name in ALLOWED_TAGS -> element(token)
            }
        }

        private fun element(token: HtmlToken.Start) {
            val name = token.name
            if (name == "a" && "a" in open) end("a")
            if (open.size < MAX_DEPTH || name in VOID_TAGS) {
                out.append('<').append(name)
                token.attributes.forEach { (attribute, value) ->
                    attribute(name, attribute, value)?.let(out::append)
                }
                out.append('>')
                if (name !in VOID_TAGS) open.add(name)
            }
        }

        private fun attribute(tag: String, name: String, value: String): String? = when {
            name == "style" -> styleAttribute(value)

            tag == "a" && name == "href" -> href(value)

            tag == "img" && name == "src" -> image("src", "data-blocked-src", value)

            name == "background" && tag in BACKGROUND_TAGS -> image(name, null, value)

            name in GLOBAL_ATTRIBUTES && value.length <= MAX_ATTRIBUTE_LENGTH ->
                attributeText(name, HtmlEntities.decode(value))

            else -> null
        }

        private fun styleAttribute(value: String): String? =
            if (value.length > MAX_ATTRIBUTE_LENGTH) {
                null
            } else {
                css.declarations(HtmlEntities.decode(value))
                    .takeIf { it.isNotEmpty() }
                    ?.let { attributeText("style", it) }
            }

        private fun href(value: String): String? {
            val url = UrlPolicy.clean(HtmlEntities.decode(value))
            val kind = UrlPolicy.kind(url)
            val keep = value.length <= MAX_ATTRIBUTE_LENGTH && kind in LINK_KINDS
            if (keep && kind != UrlKind.FRAGMENT) currentLink = LinkBuilder(url, kind)
            return if (keep) {
                attributeText("href", url) + attributeText("rel", "noopener noreferrer nofollow")
            } else {
                null
            }
        }

        /** An image reference: kept when local, parked in [blockedName] when remote. */
        private fun image(name: String, blockedName: String?, value: String): String? {
            val url = absolute(UrlPolicy.clean(HtmlEntities.decode(value)))
            val kind = UrlPolicy.kind(url)
            val limit = if (kind ==
                UrlKind.DATA_IMAGE
            ) {
                MAX_DATA_IMAGE_LENGTH
            } else {
                MAX_ATTRIBUTE_LENGTH
            }
            return when {
                value.length > limit -> null

                kind == UrlKind.CID || kind == UrlKind.DATA_IMAGE -> attributeText(name, url)

                kind == UrlKind.HTTPS && allowRemote -> attributeText(name, url)

                UrlPolicy.isRemote(kind) -> {
                    blockedImages++
                    blockedName?.let { attributeText(it, url) }
                }

                else -> null
            }
        }

        private fun end(name: String) {
            val index = open.lastIndexOf(name)
            if (index >= 0) while (open.size > index) close()
        }

        private fun close() {
            val name = open.removeAt(open.lastIndex)
            out.append("</").append(name).append('>')
            if (name == "a") {
                currentLink?.let { links.add(it.build()) }
                currentLink = null
            }
        }
    }

    /** Collects the visible text of a link while its content is being read. */
    private class LinkBuilder(private val href: String, private val kind: UrlKind) {
        private val text = StringBuilder()

        fun append(part: String) {
            val room = MAX_LINK_TEXT - text.length
            if (room > 0) text.append(part, 0, minOf(room, part.length))
        }

        fun build(): HtmlLink {
            val visible = text.toString().trim().replace(BLANKS, " ")
            val web = kind == UrlKind.HTTPS || kind == UrlKind.HTTP
            return HtmlLink(href, visible, web && LinkAnalysis.looksLikeDifferentUrl(visible, href))
        }
    }

    private companion object {
        const val MAX_DEPTH = 512
        const val MAX_ATTRIBUTE_LENGTH = 8_192
        const val MAX_DATA_IMAGE_LENGTH = 2_000_000
        const val MAX_LINK_TEXT = 1_000
        val BLANKS = Regex("\\s+")

        val LINK_KINDS = setOf(
            UrlKind.HTTPS,
            UrlKind.HTTP,
            UrlKind.MAILTO,
            UrlKind.TEL,
            UrlKind.FRAGMENT
        )
        val VOID_TAGS = setOf("br", "hr", "img", "col", "wbr")
        val BACKGROUND_TAGS = setOf("table", "tr", "td", "th")
        val RAW_TEXT_DROPPED =
            setOf("script", "iframe", "textarea", "title", "xmp", "noembed", "noframes")
        val DROPPED_ELEMENTS = setOf(
            "object", "applet", "svg", "math", "template", "select", "frameset", "audio", "video",
            "canvas"
        )
        val ALLOWED_TAGS = setOf(
            "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote",
            "br", "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "details",
            "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2",
            "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark",
            "nav", "ol", "p", "pre", "q", "s", "samp", "section", "small", "span", "strike",
            "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead",
            "time", "tr", "tt", "u", "ul", "var", "wbr"
        )
        val GLOBAL_ATTRIBUTES = setOf(
            "class", "title", "lang", "dir", "align", "valign", "width", "height", "bgcolor",
            "color", "border", "cellpadding", "cellspacing", "colspan", "rowspan", "nowrap",
            "face", "size", "alt", "abbr", "scope", "headers", "span", "start", "datetime",
            "hspace", "vspace", "noshade"
        )

        fun absolute(url: String) = if (url.startsWith("//")) "https:$url" else url

        fun escapeText(raw: String): String =
            if ('<' in raw || '>' in raw) raw.replace("<", "&lt;").replace(">", "&gt;") else raw

        fun attributeText(name: String, value: String): String {
            val escaped = value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
            return " $name=\"$escaped\""
        }
    }
}
