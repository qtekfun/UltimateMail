// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import java.text.BreakIterator
import java.util.Locale

/**
 * What a sender avatar shows: one [initial] (a letter, digit or emoji) on a circle whose colour
 * is one of [PALETTE_SIZE] slots, [colorIndex]. The UI maps the slot to a real colour.
 */
data class AvatarSpec(val initial: String, val colorIndex: Int) {
    companion object {
        /** Number of colour slots; the UI palette must have exactly this many colours. */
        const val PALETTE_SIZE = 10

        private const val UNKNOWN = "?"

        /**
         * The avatar of a sender. The colour depends only on the address (case-insensitive), so
         * the same person looks the same in every list and every launch; the initial comes from
         * the display name, then from the address when the name has none.
         */
        fun of(name: String, address: String): AvatarSpec {
            val colorSource = address.trim().lowercase(Locale.ROOT).ifEmpty {
                name.trim().lowercase(Locale.ROOT)
            }
            val initial = firstSymbol(name) ?: firstSymbol(address) ?: UNKNOWN
            return AvatarSpec(initial, colorIndexOf(colorSource, PALETTE_SIZE))
        }

        /** Stable slot in 0 until [slots] for [text]: it never depends on the process or device. */
        fun colorIndexOf(text: String, slots: Int): Int = Math.floorMod(text.hashCode(), slots)

        /** First user-perceived character that is not space or punctuation, upper-cased. */
        private fun firstSymbol(text: String): String? {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(text)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val cluster = text.substring(start, end)
                if (isSymbol(cluster.codePointAt(0))) return cluster.uppercase(Locale.ROOT)
                start = end
                end = iterator.next()
            }
            return null
        }

        private fun isSymbol(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
            Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
    }
}
