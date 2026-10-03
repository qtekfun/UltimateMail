// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import kotlin.math.abs

private const val THIRTY_DAYS = 30
private const val NINETY_DAYS = 90
private const val HALF_YEAR_DAYS = 180
private const val YEAR_DAYS = 365

/**
 * The offline window choices of an account (RF-10) and the number of days each one is stored as
 * (`AccountEntity.offlineWindowDays`); [ALL] keeps the whole mailbox and is stored as null.
 */
enum class OfflineWindow(val days: Int?) {
    DAYS_30(THIRTY_DAYS),
    DAYS_90(NINETY_DAYS),
    DAYS_180(HALF_YEAR_DAYS),
    YEAR(YEAR_DAYS),
    ALL(null);

    companion object {
        /**
         * The choice for a stored value. A number of days that is not one of the choices (only
         * possible if stored by something else) is shown as the closest one.
         */
        fun fromDays(days: Int?): OfflineWindow = when (days) {
            null -> ALL
            else -> entries.filter { it.days != null }.minBy { abs(it.days!! - days) }
        }
    }
}
