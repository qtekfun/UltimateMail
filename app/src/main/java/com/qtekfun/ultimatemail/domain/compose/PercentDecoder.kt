// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import java.io.ByteArrayOutputStream

/** Percent-decoding of the text of a `mailto:` link. */
internal object PercentDecoder {
    private const val RADIX = 16
    private const val ESCAPE_LENGTH = 3

    /**
     * Percent-decoding as UTF-8. A `+` stays a plus (mailto is not a form), and a `%` that is
     * not followed by two hex digits stays as it is instead of failing the whole value.
     */
    fun decode(text: String): String {
        val out = StringBuilder()
        val pending = ByteArrayOutputStream()

        fun flush() {
            if (pending.size() > 0) {
                out.append(pending.toString(Charsets.UTF_8.name()))
                pending.reset()
            }
        }
        var i = 0
        while (i < text.length) {
            val hex = if (text[i] == '%' && i + 2 < text.length) hexByte(text, i + 1) else null
            if (hex != null) {
                pending.write(hex)
                i += ESCAPE_LENGTH
            } else {
                flush()
                out.append(text[i])
                i++
            }
        }
        flush()
        return out.toString()
    }

    private fun hexByte(text: String, at: Int): Int? {
        val high = Character.digit(text[at], RADIX)
        val low = Character.digit(text[at + 1], RADIX)
        return if (high >= 0 && low >= 0) high * RADIX + low else null
    }
}
