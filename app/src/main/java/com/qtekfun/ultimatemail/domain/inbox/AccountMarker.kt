// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.domain.account.AccountSummary

/** The small marker that tells accounts apart in the unified inbox: a colour and a short name. */
data class AccountMarker(val name: String, val colorIndex: Int) {
    companion object {
        /** Number of colour slots; it is the avatar palette, so accounts look like senders. */
        const val PALETTE_SIZE = AvatarSpec.PALETTE_SIZE

        fun of(account: AccountSummary) = AccountMarker(
            name = account.displayName.ifBlank { account.email.substringBefore('@') },
            colorIndex = Math.floorMod(account.id, PALETTE_SIZE.toLong()).toInt()
        )
    }
}
