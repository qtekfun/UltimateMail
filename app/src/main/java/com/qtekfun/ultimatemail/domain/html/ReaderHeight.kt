// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.html

/** Keeps the height of a message's web view, which sits inside a scrolling thread, sane. */
object ReaderHeight {
    /** Compose layout constraints top out at 2^18 - 1 px; stay well under, and under GPU limits. */
    const val MAX_PX = 120_000

    /** Never zero: a view of no height is never laid out or drawn by some devices. */
    const val MIN_PX = 1

    fun clamp(measuredPx: Int): Int = measuredPx.coerceIn(MIN_PX, MAX_PX)
}
