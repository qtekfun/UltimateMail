// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/**
 * A message body cut in two: what the sender wrote ([visible]) and the earlier mail quoted below
 * it ([quoted], null when there is nothing to fold away). Both parts together are the original.
 */
data class QuoteSplit(val visible: String, val quoted: String?) {
    val hasQuote: Boolean get() = quoted != null

    companion object {
        fun whole(text: String) = QuoteSplit(text, null)
    }
}
