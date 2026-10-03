// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/**
 * Decodes the character references browsers honour in attribute values, so a value is judged by
 * what it will mean (`&#106;avascript&colon;` is `javascript:`) and not by how it is spelled.
 */
internal object HtmlEntities {
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val REPLACEMENT = 0xFFFD
    private const val HEX_RADIX = 16
    private const val DECIMAL_RADIX = 10
    private const val MAX_NAME_LENGTH = 8
    private const val MAX_DIGITS = 7
    private const val HEX_PREFIX_LENGTH = 3
    private const val DECIMAL_PREFIX_LENGTH = 2

    /** Named references that matter for URLs and common text; others are left untouched. */
    private val namedValues = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00a0", "tab" to "\t", "newline" to "\n", "colon" to ":", "lpar" to "(",
        "rpar" to ")", "sol" to "/", "bsol" to "\\", "period" to ".", "comma" to ",",
        "semi" to ";", "equals" to "=", "quest" to "?", "num" to "#", "percnt" to "%",
        "plus" to "+", "excl" to "!", "commat" to "@", "lowbar" to "_", "hyphen" to "-"
    )

    /** References the HTML spec accepts without a trailing semicolon. */
    private val legacy = setOf("amp", "lt", "gt", "quot", "nbsp")

    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i = if (c == '&') decodeAt(text, i, out) else out.append(c).let { i + 1 }
        }
        return out.toString()
    }

    /** Decodes the reference starting at [start] (a `&`) and returns the next index to read. */
    private fun decodeAt(text: String, start: Int, out: StringBuilder): Int {
        val next = text.getOrNull(start + 1)
        val consumed = when {
            next == '#' -> numeric(text, start, out)
            next != null && next.isLetter() -> namedReference(text, start, out)
            else -> 0
        }
        if (consumed == 0) out.append('&')
        return start + maxOf(consumed, 1)
    }

    /** Returns the number of characters consumed from [start], 0 when this is not a reference. */
    private fun numeric(text: String, start: Int, out: StringBuilder): Int {
        val hex = text.getOrNull(start + 2).let { it == 'x' || it == 'X' }
        val digitsFrom = start + if (hex) HEX_PREFIX_LENGTH else DECIMAL_PREFIX_LENGTH
        val radix = if (hex) HEX_RADIX else DECIMAL_RADIX
        var end = digitsFrom
        while (end < text.length && Character.digit(text[end], radix) >= 0) end++
        if (end == digitsFrom) return 0
        // Anything longer than this is out of range anyway; never parse an unbounded number.
        val codePoint = if (end - digitsFrom > MAX_DIGITS) {
            REPLACEMENT
        } else {
            text.substring(digitsFrom, end).toInt(radix)
        }
        out.appendCodePoint(validCodePoint(codePoint))
        return (if (text.getOrNull(end) == ';') end + 1 else end) - start
    }

    private fun validCodePoint(codePoint: Int): Int = when {
        codePoint == 0 || codePoint > MAX_CODE_POINT -> REPLACEMENT
        codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> REPLACEMENT
        else -> codePoint
    }

    private fun namedReference(text: String, start: Int, out: StringBuilder): Int {
        var end = start + 1
        while (end < text.length && end - start <= MAX_NAME_LENGTH && text[end].isLetterOrDigit()) {
            end++
        }
        val name = text.substring(start + 1, end).lowercase()
        val closed = text.getOrNull(end) == ';'
        val value = namedValues[name]
        return if (value != null && (closed || name in legacy)) {
            out.append(value)
            end - start + if (closed) 1 else 0
        } else {
            0
        }
    }
}
