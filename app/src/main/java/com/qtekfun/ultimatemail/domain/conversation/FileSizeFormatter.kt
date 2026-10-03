// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import java.util.Locale

/** Writes a size in bytes the way people read it: "812 B", "1.4 MB". Units are 1024-based. */
object FileSizeFormatter {
    private val units = listOf("B", "KB", "MB", "GB", "TB")
    private const val STEP = 1024.0
    private const val ONE_DECIMAL_BELOW = 10

    fun format(bytes: Long, locale: Locale): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= STEP && unit < units.lastIndex) {
            value /= STEP
            unit++
        }
        return if (unit == 0) {
            String.format(locale, "%d %s", value.toLong(), units[0])
        } else {
            // One decimal below 10 ("1.4 MB"), none above ("120 MB").
            val pattern = if (value < ONE_DECIMAL_BELOW) "%.1f %s" else "%.0f %s"
            String.format(locale, pattern, value, units[unit])
        }
    }
}
