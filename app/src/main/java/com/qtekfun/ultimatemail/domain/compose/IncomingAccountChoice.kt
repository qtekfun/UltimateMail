// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

/** Which account a message started from another app is written from. */
sealed interface AccountChoice {
    /** There is no account at all. */
    data object None : AccountChoice

    data class Use(val accountId: Long) : AccountChoice

    /** Several accounts and none is on screen: ask the user. */
    data class Ask(val accountIds: List<Long>) : AccountChoice
}

/** The default account for a message started from outside (RF-07). */
object IncomingAccountChoice {
    /** The account being shown, else the only one, else ask. */
    fun choose(accountIds: List<Long>, shown: Long?): AccountChoice = when {
        accountIds.isEmpty() -> AccountChoice.None
        shown != null && shown in accountIds -> AccountChoice.Use(shown)
        accountIds.size == 1 -> AccountChoice.Use(accountIds.single())
        else -> AccountChoice.Ask(accountIds)
    }
}
