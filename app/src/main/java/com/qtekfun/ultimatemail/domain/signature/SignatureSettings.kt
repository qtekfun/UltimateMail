// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.signature

/** What the user is composing: it decides where a signature goes relative to quoted text. */
enum class ComposeKind { NEW, REPLY, FORWARD }

/**
 * The signature settings of one account (RF-08), as stored in the account: [text] is plain text,
 * [enabled] switches it off without losing it, and [beforeQuote] puts it above the quoted text
 * of replies and forwards (below it when false).
 */
data class SignatureSettings(
    val text: String,
    val enabled: Boolean = true,
    val beforeQuote: Boolean = true
) {
    /**
     * The lines of the signature block, starting with the `-- ` delimiter, or null when this
     * account adds nothing (disabled or blank text), so no stray delimiter is ever produced.
     */
    internal val block: List<String>? = run {
        val lines = text.split("\r\n", "\n").map { it.trimEnd() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        if (enabled && lines.isNotEmpty()) listOf(SignatureEditor.DELIMITER) + lines else null
    }
}
