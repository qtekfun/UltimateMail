// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Brings a swiped row back to its place. The animation behind [reset] can be refused while the
 * release animation of the same gesture is still running (it throws a cancellation that is not
 * ours), and a row that is left swiped away stays on screen with nothing to undo it. So a refused
 * reset is tried again shortly, and if it never takes, [snap] puts the row back without animating.
 * A cancellation of the calling coroutine itself is always passed on.
 */
internal suspend fun springBack(
    isAway: () -> Boolean,
    reset: suspend () -> Unit,
    snap: suspend () -> Unit,
    attempts: Int = SPRING_BACK_ATTEMPTS,
    retryMillis: Long = SPRING_BACK_RETRY_MILLIS
) {
    repeat(attempts) {
        if (!isAway()) return
        try {
            reset()
        } catch (ignored: CancellationException) {
            currentCoroutineContext().ensureActive()
            delay(retryMillis)
        }
    }
    if (isAway()) snap()
}

private const val SPRING_BACK_ATTEMPTS = 10
private const val SPRING_BACK_RETRY_MILLIS = 50L
