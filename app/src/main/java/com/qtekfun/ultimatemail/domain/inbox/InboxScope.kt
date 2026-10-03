// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/** Which conversations a list shows: one folder of one account, or every account's inbox. */
sealed interface InboxScope {
    /** Stable text form used to keep the scope in saved state. */
    val key: String

    data class Folder(val accountId: Long, val path: String) : InboxScope {
        override val key = "folder/$accountId/$path"
    }

    /** The INBOX folders of all accounts merged, newest first (RF-03). */
    data object Unified : InboxScope {
        override val key = "unified"
    }

    companion object {
        /** The scope for a [key] produced by [InboxScope.key]; null when it is not one. */
        fun fromKey(key: String?): InboxScope? {
            if (key == null) return null
            if (key == Unified.key) return Unified
            val parts = key.split('/', limit = FOLDER_KEY_PARTS)
            val accountId = parts.getOrNull(1)?.toLongOrNull()
            val path = parts.getOrNull(2)
            return if (parts[0] == "folder" && accountId != null && !path.isNullOrEmpty()) {
                Folder(accountId, path)
            } else {
                null
            }
        }

        private const val FOLDER_KEY_PARTS = 3
    }
}
