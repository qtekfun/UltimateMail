// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

/** The part of the queue the undo snackbar needs: letting go of what it held back. */
fun interface HeldOperations {
    /** The undo window is over: what was held back (see [NewOperation.holdFor]) may go now. */
    suspend fun releaseHeld(accountId: Long)
}
