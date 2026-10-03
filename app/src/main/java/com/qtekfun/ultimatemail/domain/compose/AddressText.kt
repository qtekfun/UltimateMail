// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

/** The text-level work of reading recipient lists: where entries end and how names are quoted. */
internal object AddressText {
    /** Tracks quotes, angle brackets and comments while a recipient list is read character by character. */
    private class Scanner {
        private var quoted = false
        private var escaped = false
        private var angle = 0
        private var comment = 0

        /** Reads [c]; true when it is a separator between two recipients. */
        fun separates(c: Char): Boolean {
            val separator =
                !escaped && !quoted && angle == 0 && comment == 0 && (c == ',' || c == ';')
            if (!separator) track(c)
            return separator
        }

        private fun track(c: Char) {
            when {
                escaped -> escaped = false
                c == '\\' && (quoted || comment > 0) -> escaped = true
                c == '"' && comment == 0 -> quoted = !quoted
                quoted -> Unit
                c == '<' -> angle++
                c == '>' && angle > 0 -> angle--
                c == '(' -> comment++
                c == ')' && comment > 0 -> comment--
            }
        }
    }

    /** Cuts [text] at commas and semicolons that are not inside quotes, brackets or comments. */
    fun split(text: String): List<String> {
        val scanner = Scanner()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (c in text) {
            if (scanner.separates(c)) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** Index of the last [target] outside quotes, or -1. */
    fun lastUnquoted(text: String, target: Char): Int {
        var quoted = false
        var escaped = false
        var found = -1
        text.forEachIndexed { index, c ->
            when {
                escaped -> escaped = false
                quoted && c == '\\' -> escaped = true
                c == '"' -> quoted = !quoted
                !quoted && c == target -> found = index
            }
        }
        return found
    }

    fun unquote(raw: String): String? {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length - 1).replace(Regex("\\\\(.)"), "$1")
        }
        return raw.takeIf { it.isNotEmpty() }
    }
}
