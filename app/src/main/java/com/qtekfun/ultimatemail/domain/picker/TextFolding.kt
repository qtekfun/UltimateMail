// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import java.text.Normalizer
import java.util.Locale

/**
 * Text prepared for matching: lower case, compatibility-decomposed (so "Ü", "ü" and "u" all
 * become "u", and a full-width "Ａ" becomes "a") with the combining accents removed. [starts] and
 * [ends] map every character of [text] back to the characters of the original it came from, so a
 * match can be highlighted in the text the user sees.
 */
class FoldedText internal constructor(
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray
) {
    /** The range of the original text covered by the folded range [from] until [until]. */
    fun originalRange(from: Int, until: Int): IntRange {
        require(from in 0 until until && until <= text.length) { "Range outside the text" }
        return starts[from] until ends[until - 1]
    }
}

/** Case- and accent-insensitive folding for the folder search. */
object TextFolding {
    private const val FIRST_COMBINING = 0x0300
    private const val LAST_COMBINING = 0x036F
    private const val ASCII_LIMIT = 0x80

    fun fold(source: String): FoldedText {
        val out = StringBuilder(source.length)
        val starts = IntArray(source.length * MAX_EXPANSION)
        val ends = IntArray(source.length * MAX_EXPANSION)
        var index = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val width = Character.charCount(codePoint)
            val before = out.length
            appendFolded(out, codePoint)
            for (position in before until out.length) {
                starts[position] = index
                ends[position] = index + width
            }
            index += width
        }
        return FoldedText(out.toString(), starts.copyOf(out.length), ends.copyOf(out.length))
    }

    /** Just the folded text, when no mapping back is needed. */
    fun foldedString(source: String): String = fold(source).text

    private fun appendFolded(out: StringBuilder, codePoint: Int) {
        if (codePoint < ASCII_LIMIT) {
            out.append(Character.toLowerCase(codePoint.toChar()))
            return
        }
        val lower = String(Character.toChars(codePoint)).lowercase(Locale.ROOT)
        Normalizer.normalize(lower, Normalizer.Form.NFKD).forEach { char ->
            if (char.code !in FIRST_COMBINING..LAST_COMBINING) out.append(char)
        }
    }

    /** NFKD can turn one character into many (U+FDFA becomes 18); this is a safe upper bound. */
    private const val MAX_EXPANSION = 20
}
