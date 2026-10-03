// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

import java.util.Locale

/**
 * A link that survived sanitizing. [text] is what the reader sees on it and [deceptive] says that
 * text looks like a web address that leads somewhere else than [href]: the UI should make the
 * reader confirm the real destination.
 */
data class HtmlLink(val href: String, val text: String, val deceptive: Boolean)

/** Detects link text that pretends to be a different address from the real one. */
object LinkAnalysis {
    private val fileExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "png", "jpg", "jpeg", "gif",
        "txt", "csv", "odt", "ods", "rtf", "html", "htm", "exe", "apk", "mp3", "mp4"
    )
    private val WHITESPACE = Regex("\\s+")
    private val WEB_SCHEMES = setOf("http", "https")
    private const val MIN_TLD_LENGTH = 2
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>\"'"

    /** True when [visibleText] contains a web address whose host is not that of [href]. */
    fun looksLikeDifferentUrl(visibleText: String, href: String): Boolean {
        val target = hostOf(href)
        return target != null && visibleText.split(WHITESPACE).any { word ->
            val candidate = word.trim { it in "([<\"'" }.trimEnd { it in TRAILING_PUNCTUATION }
            looksLikeAddress(candidate) && differs(candidate, target)
        }
    }

    /** Lower-case host of an http(s) URL without user info or port, null for anything else. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val isWeb = schemeEnd >= 0 && url.take(schemeEnd).lowercase(Locale.ROOT) in WEB_SCHEMES
        val hostAndPort = if (isWeb) authorityOf(url).substringAfterLast('@') else ""
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']') + "]"
        } else {
            hostAndPort.substringBefore(':')
        }
        return host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    private fun authorityOf(url: String): String =
        url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }

    private fun differs(candidate: String, targetHost: String): Boolean {
        val address = if ("://" in candidate) candidate else "http://$candidate"
        val shown = hostOf(address)?.removePrefix("www.")
        val real = targetHost.removePrefix("www.")
        val related = shown != null &&
            (shown == real || real.endsWith(".$shown") || shown.endsWith(".$real"))
        return '@' in authorityOf(address) || !related
    }

    private fun looksLikeAddress(word: String): Boolean {
        val hasScheme = word.startsWith("http://", true) || word.startsWith("https://", true)
        return hasScheme || word.startsWith("www.", true) || looksLikeDomain(word)
    }

    /** `example.com` or `shop.example.co.uk/path`, but not `report.pdf` or `e.g`. */
    private fun looksLikeDomain(word: String): Boolean {
        val host = word.takeWhile { it != '/' && it != '?' && it != '#' }
        val labels = host.split('.')
        val tld = labels.last().lowercase(Locale.ROOT)
        val wellFormed = labels.size >= 2 && labels.all { label ->
            label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
        }
        return wellFormed && tld.length >= MIN_TLD_LENGTH && tld.all { it.isLetter() } &&
            tld !in fileExtensions
    }
}
