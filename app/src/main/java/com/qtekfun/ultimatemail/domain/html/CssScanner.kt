// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** Tracks quotes, brackets and braces while text is read one character at a time. */
internal class Nesting(var braces: Int = 0) {
    private var quote = NO_QUOTE
    private var brackets = 0

    /** True when the next character is outside quotes and `()`/`[]`. */
    val isTopLevel: Boolean get() = quote == NO_QUOTE && brackets == 0

    fun feed(c: Char) {
        when {
            quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
            c == '"' || c == '\'' -> quote = c
            c == '(' || c == '[' -> brackets++
            c == ')' || c == ']' -> brackets = maxOf(0, brackets - 1)
            c == '{' -> braces++
            c == '}' -> braces--
        }
    }

    private companion object {
        const val NO_QUOTE = '\u0000'
    }
}

/** Finds the structure of normalised CSS text; every function moves forward only. */
internal object CssScanner {
    /** Splits at top-level semicolons, honouring quotes and brackets. */
    fun splitDeclarations(text: String): List<String> {
        val parts = ArrayList<String>()
        val nesting = Nesting()
        var from = 0
        for (i in text.indices) {
            if (text[i] == ';' && nesting.isTopLevel) {
                parts.add(text.substring(from, i))
                from = i + 1
            }
            nesting.feed(text[i])
        }
        parts.add(text.substring(from))
        return parts
    }

    /** Index of the first character from [from] that is not blank or a stray `}`. */
    fun skipBlanks(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i].isWhitespace() || text[i] == '}')) i++
        return i
    }

    /** Index of the `{` or `;` ending a rule prelude outside quotes and brackets, else the end. */
    fun preludeEnd(text: String, from: Int): Int {
        val nesting = Nesting()
        var i = from
        while (i < text.length && !(nesting.isTopLevel && text[i] in "{;")) {
            nesting.feed(text[i])
            i++
        }
        return i
    }

    /** Index of the `}` closing a block whose body starts at [from], else the end of the text. */
    fun blockEnd(text: String, from: Int): Int {
        val nesting = Nesting(braces = 1)
        var i = from
        while (i < text.length) {
            nesting.feed(text[i])
            if (nesting.braces == 0) break
            i++
        }
        return i
    }
}
