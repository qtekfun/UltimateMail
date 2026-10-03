// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/**
 * What the selection bar offers for a set of selected conversations. Archive and delete are on
 * when at least one of them can take it; the others are skipped. Read and star are smart
 * toggles: if any selected conversation is unread (or not starred) they set it for all,
 * otherwise they clear it for all.
 */
data class BulkAvailability(
    val canArchive: Boolean,
    val canDelete: Boolean,
    val readChange: RowChange,
    val starChange: RowChange,
    /** Moving asks for one destination, which only makes sense inside one account. */
    val canMove: Boolean
) {
    companion object {
        fun of(items: List<ConversationItem>, targets: RowTargets): BulkAvailability {
            val all = items.map { targets.of(it) }
            return BulkAvailability(
                canArchive = all.any { it?.canArchive == true },
                canDelete = all.any { it?.canDelete == true },
                readChange = if (items.any { it.unread }) {
                    RowChange.MARK_READ
                } else {
                    RowChange.MARK_UNREAD
                },
                starChange = if (items.any { !it.flagged }) RowChange.STAR else RowChange.UNSTAR,
                canMove = items.isNotEmpty() && items.map { it.accountId }.distinct().size == 1
            )
        }
    }
}
