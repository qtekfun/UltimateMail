// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.model.AuthType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the account switcher needs to know about an account. */
data class AccountSummary(
    val id: Long,
    val email: String,
    val displayName: String,
    val authType: AuthType
)

/** The accounts stored on the device, in the order they were added. */
class AccountListing @Inject constructor(database: UltimateMailDatabase) {
    private val accounts = database.accountDao()

    fun observe(): Flow<List<AccountSummary>> = accounts.observeAll().map { list ->
        list.map { AccountSummary(it.id, it.email, it.displayName, it.authType) }
    }
}
