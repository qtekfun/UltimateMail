// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/**
 * Turns CSS into plain text before it is judged: escapes are resolved, NUL is dropped and
 * comments are removed. Escapes go first so that an escape spelling a comment opener cannot
 * start a comment later.
 */
internal object CssNormalizer {
    private const val HEX = 16
    private const val MAX_HEX = 6
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val REPLACEMENT = 0xFFFD
    private val surrogates = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

    fun normalize(raw: String): String = stripComments(unescape(raw))

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            i = if (c == '\\') escape(raw, i + 1, out) else out.append(c).let { i + 1 }
        }
        return out.toString().replace("\u0000", "")
    }

    /** Handles the escape whose first character is at [from]; returns the next index to read. */
    private fun escape(raw: String, from: Int, out: StringBuilder): Int {
        val first = raw.getOrNull(from)
        return when {
            first == null -> from

            Character.digit(first, HEX) >= 0 -> hexEscape(raw, from, out)

            else -> {
                if (first != '\n') out.append(first)
                from + 1
            }
        }
    }

    /** `\41 ` style escape: up to six hex digits and one optional blank after them. */
    private fun hexEscape(raw: String, from: Int, out: StringBuilder): Int {
        var end = from
        while (end < raw.length && end - from < MAX_HEX &&
            Character.digit(raw[end], HEX) >= 0
        ) {
            end++
        }
        val code = raw.substring(from, end).toInt(HEX)
        val valid = code != 0 && code <= MAX_CODE_POINT && code !in surrogates
        out.appendCodePoint(if (valid) code else REPLACEMENT)
        return if (end < raw.length && raw[end].isWhitespace()) end + 1 else end
    }

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("/*", i)) {
                val close = text.indexOf("*/", i + 2)
                i = if (close < 0) text.length else close + 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}
