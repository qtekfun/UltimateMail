// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import java.util.Locale

/**
 * The "to me" line under a sender: whether the account's owner is among the recipients, the
 * first few others ([names]) and how many more there are ([hiddenCount]).
 */
data class RecipientSummary(
    val includesMe: Boolean,
    val names: List<String>,
    val hiddenCount: Int
) {
    /** Only the owner is addressed: the line reads "to me". */
    val onlyMe: Boolean get() = includesMe && names.isEmpty() && hiddenCount == 0

    companion object {
        /** Others named before the rest collapses into "+N". */
        const val MAX_NAMES = 2

        /**
         * Summarizes the To and Cc recipients of a message. Addresses equal to one of
         * [ownAddresses] (any case) are "me"; duplicates count once.
         */
        fun of(
            to: List<String>,
            cc: List<String>,
            ownAddresses: Set<String>,
            maxNames: Int = MAX_NAMES
        ): RecipientSummary {
            val own = ownAddresses.map { it.trim().lowercase(Locale.ROOT) }.toSet()
            val all = (to + cc).map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val others = all.filter { it.lowercase(Locale.ROOT) !in own }
            return RecipientSummary(
                includesMe = others.size < all.size,
                names = others.take(maxNames),
                hiddenCount = (others.size - maxNames).coerceAtLeast(0)
            )
        }
    }
}
