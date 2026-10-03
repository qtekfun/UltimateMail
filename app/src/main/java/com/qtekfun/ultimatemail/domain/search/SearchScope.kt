// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.domain.inbox.InboxScope

/** Where a search looks: one folder, one account or every account (RF-09). */
sealed interface SearchScope {
    /** Stable text form, used in routes and saved state. */
    val key: String

    /** The account the scope is limited to; null for [AllAccounts]. */
    val accountId: Long?

    data class Folder(override val accountId: Long, val path: String) : SearchScope {
        override val key = "folder/$accountId/$path"
    }

    data class Account(override val accountId: Long) : SearchScope {
        override val key = "account/$accountId"
    }

    data object AllAccounts : SearchScope {
        override val key = "all"
        override val accountId: Long? = null
    }

    companion object {
        /**
         * The scope a search starts with when opened from [shown]: the folder being looked at,
         * or every account from the unified inbox (and when nothing is shown).
         */
        fun startingFrom(shown: InboxScope?): SearchScope = when (shown) {
            is InboxScope.Folder -> Folder(shown.accountId, shown.path)
            InboxScope.Unified, null -> AllAccounts
        }

        /** The scope for a [key] produced by [SearchScope.key]; null when it is not one. */
        fun fromKey(key: String?): SearchScope? = when (key) {
            null -> null
            AllAccounts.key -> AllAccounts
            else -> keyedScope(key)
        }

        private fun keyedScope(key: String): SearchScope? {
            val parts = key.split('/', limit = FOLDER_KEY_PARTS)
            val accountId = parts.getOrNull(1)?.toLongOrNull()
            return when {
                accountId == null -> null

                parts[0] == "account" && parts.size == ACCOUNT_KEY_PARTS -> Account(accountId)

                parts[0] == "folder" && parts.size == FOLDER_KEY_PARTS && parts[2].isNotEmpty() ->
                    Folder(accountId, parts[2])

                else -> null
            }
        }

        private const val ACCOUNT_KEY_PARTS = 2
        private const val FOLDER_KEY_PARTS = 3
    }
}
