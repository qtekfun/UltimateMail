// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** What a URL found in an e-mail points at, as far as safety is concerned. */
enum class UrlKind { HTTPS, HTTP, MAILTO, TEL, CID, DATA_IMAGE, FRAGMENT, PROTOCOL_RELATIVE, OTHER }

/**
 * Classification of URLs from untrusted HTML. Values are cleaned the way browsers read them
 * (blanks and control characters inside a URL are ignored, so `java\tscript:` is `javascript:`)
 * and the cleaned form is what gets emitted, so what was judged is what the browser receives.
 */
object UrlPolicy {

    private val safeImageTypes = setOf("png", "jpeg", "jpg", "gif", "webp", "bmp", "avif", "x-icon")
    private const val BASE64_MARKER = ";base64,"
    private const val DATA_IMAGE_PREFIX = "data:image/"

    /** Removes whitespace, control and invisible formatting characters anywhere in [raw]. */
    fun clean(raw: String): String = raw.filterNot(::ignoredByBrowsers)

    private fun ignoredByBrowsers(c: Char): Boolean =
        c.isISOControl() || c.isWhitespace() || Character.getType(c) == Character.FORMAT.toInt()

    /** Classifies an already [clean]ed URL. */
    fun kind(url: String): UrlKind {
        val scheme = scheme(url)
        return when {
            url.startsWith("//") -> UrlKind.PROTOCOL_RELATIVE
            url.startsWith("#") -> UrlKind.FRAGMENT
            scheme == "https" -> UrlKind.HTTPS
            scheme == "http" -> UrlKind.HTTP
            scheme == "mailto" -> UrlKind.MAILTO
            scheme == "tel" -> UrlKind.TEL
            scheme == "cid" -> UrlKind.CID
            scheme == "data" && isSafeDataImage(url) -> UrlKind.DATA_IMAGE
            else -> UrlKind.OTHER
        }
    }

    /** True for the kinds that point outside the message and would be fetched from the network. */
    fun isRemote(kind: UrlKind): Boolean =
        kind == UrlKind.HTTPS || kind == UrlKind.HTTP || kind == UrlKind.PROTOCOL_RELATIVE

    /** The scheme in lower case, or null when [url] is relative or has an invalid scheme. */
    private fun scheme(url: String): String? {
        val colon = url.indexOf(':')
        val candidate = if (colon > 0) url.substring(0, colon) else return null
        val valid = candidate[0].isAsciiLetter() &&
            candidate.all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }
        return if (valid) candidate.lowercase() else null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** A `data:image/<type>;base64,<payload>` URL of a raster type (never SVG). */
    private fun isSafeDataImage(url: String): Boolean {
        if (!url.regionMatches(0, DATA_IMAGE_PREFIX, 0, DATA_IMAGE_PREFIX.length, true)) {
            return false
        }
        val marker = url.indexOf(BASE64_MARKER, DATA_IMAGE_PREFIX.length, ignoreCase = true)
        val type = if (marker > 0) {
            url.substring(DATA_IMAGE_PREFIX.length, marker).lowercase()
        } else {
            ""
        }
        val payloadStart = marker + BASE64_MARKER.length
        return type in safeImageTypes && marker > 0 &&
            (payloadStart until url.length).all { isBase64(url[it]) }
    }

    private fun isBase64(c: Char) = c.isAsciiLetter() || c in '0'..'9' || c in "+/="
}
