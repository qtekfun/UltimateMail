// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/**
 * Allow-list style sanitizer for `style` attributes and `<style>` blocks.
 *
 * The input is normalised first ([CssNormalizer]): escapes are resolved, comments removed. Only
 * declarations that pass are written back, in plain form, so the output holds no escapes and no
 * comments and a browser reads exactly what was judged here. Declarations that can run code
 * (`expression()`, `-moz-binding`, `behavior`, `javascript:`), import or fetch resources
 * (`@import`, `url()` to anything but `cid:` and safe `data:image`, `image-set()`) or cover the
 * whole page (`position: fixed|absolute|sticky`) are dropped. Remote `https:` URLs survive only
 * when [allowRemote] is set; every remote URL met otherwise is counted in [blockedRemote].
 */
internal class CssSanitizer(private val allowRemote: Boolean) {
    /** Remote URLs found in declarations that were dropped. */
    var blockedRemote = 0
        private set

    /** Sanitizes the value of a `style` attribute. */
    fun declarations(raw: String): String = declarationsOf(CssNormalizer.normalize(raw))

    /** Sanitizes the content of a `<style>` element. */
    fun stylesheet(raw: String): String {
        val out = StringBuilder()
        rules(CssNormalizer.normalize(raw), 0, out)
        return out.toString()
    }

    private fun declarationsOf(text: String): String =
        CssScanner.splitDeclarations(text).mapNotNull(::declaration).joinToString(";")

    private fun rules(text: String, depth: Int, out: StringBuilder) {
        var i = 0
        while (i < text.length) {
            val start = CssScanner.skipBlanks(text, i)
            val end = CssScanner.preludeEnd(text, start)
            val isBlock = end < text.length && text[end] == '{'
            i = if (isBlock) {
                val close = CssScanner.blockEnd(text, end + 1)
                rule(text.substring(start, end).trim(), text.substring(end + 1, close), depth, out)
                close + 1
            } else {
                end + 1
            }
        }
    }

    private fun rule(prelude: String, body: String, depth: Int, out: StringBuilder) {
        if (prelude.startsWith("@")) {
            if (isMedia(prelude) && depth < MAX_NESTING && isSafeText(prelude)) {
                val nested = StringBuilder()
                rules(body, depth + 1, nested)
                if (nested.isNotEmpty()) out.append(prelude).append('{').append(nested).append('}')
            }
        } else if (prelude.isNotEmpty() && '<' !in prelude) {
            val declarations = declarationsOf(body)
            if (declarations.isNotEmpty()) {
                out.append(prelude).append('{').append(declarations).append('}')
            }
        }
    }

    private fun isSafeText(text: String): Boolean {
        val compact = compact(text)
        return '<' !in text && BLOCKED_TOKENS.none { it in compact } && URL_OPEN !in compact
    }

    private fun declaration(chunk: String): String? {
        val colon = chunk.indexOf(':')
        val property = if (colon > 0) chunk.substring(0, colon).trim().lowercase() else ""
        val value = if (colon > 0) chunk.substring(colon + 1).trim() else ""
        val compact = compact(value)
        val acceptable = PROPERTY.matches(property) && value.isNotEmpty() &&
            property !in BLOCKED_PROPERTIES &&
            '<' !in value &&
            BLOCKED_TOKENS.none { it in compact } &&
            !(property == "position" && OVERLAYS.any { it in compact }) &&
            urlsAcceptable(compact)
        return if (acceptable) "$property:$value" else null
    }

    /** Judges every `url()` of a declaration; the compact text is lower case without blanks. */
    private fun urlsAcceptable(compact: String): Boolean {
        var acceptable = true
        var from = compact.indexOf(URL_OPEN)
        while (from >= 0) {
            val close = compact.indexOf(')', from + URL_OPEN.length)
            if (close < 0) {
                acceptable = false
                break
            }
            val argument = compact.substring(from + URL_OPEN.length, close).trim('"', '\'')
            acceptable = judgeUrl(UrlPolicy.clean(argument)) && acceptable
            from = compact.indexOf(URL_OPEN, close)
        }
        return acceptable
    }

    private fun judgeUrl(url: String): Boolean {
        val kind = UrlPolicy.kind(url)
        val local = kind == UrlKind.CID || kind == UrlKind.DATA_IMAGE
        val allowed = local || (kind == UrlKind.HTTPS && allowRemote)
        if (!allowed && UrlPolicy.isRemote(kind)) blockedRemote++
        return allowed
    }

    private companion object {
        const val MAX_NESTING = 3
        const val URL_OPEN = "url("
        const val MEDIA = "@media"
        val PROPERTY = Regex("-{0,2}[a-z][a-z0-9-]*")
        val BLOCKED_PROPERTIES = setOf("behavior", "-ms-behavior", "-moz-binding", "binding")
        val OVERLAYS = listOf("fixed", "absolute", "sticky")
        val BLOCKED_TOKENS = listOf(
            "expression(", "javascript:", "vbscript:", "livescript:", "@import", "-moz-binding",
            "behavior:", "image-set(", "src(", "element(", "cross-fade(", "paint("
        )

        fun compact(text: String) = text.filterNot { it.isWhitespace() }.lowercase()

        fun isMedia(prelude: String) = prelude.startsWith(MEDIA, ignoreCase = true) &&
            (prelude.length == MEDIA.length || !prelude[MEDIA.length].isLetterOrDigit())
    }
}
