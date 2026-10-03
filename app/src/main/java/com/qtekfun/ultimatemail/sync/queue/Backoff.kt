// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import java.time.Duration

/** Deterministic exponential backoff: [base] doubling with every failed attempt, up to [cap]. */
class Backoff(
    private val base: Duration = Duration.ofSeconds(BASE_SECONDS),
    private val cap: Duration = Duration.ofHours(CAP_HOURS)
) {
    /** The wait after the [attempts]-th failed attempt (1 = first failure). */
    fun delayAfter(attempts: Int): Duration {
        val doublings = (attempts - 1).coerceIn(0, MAX_DOUBLINGS)
        return minOf(base.multipliedBy(1L shl doublings), cap)
    }

    private companion object {
        const val BASE_SECONDS = 30L
        const val CAP_HOURS = 6L

        /** Far past the cap for any sane base; keeps the shift from overflowing. */
        const val MAX_DOUBLINGS = 20
    }
}
