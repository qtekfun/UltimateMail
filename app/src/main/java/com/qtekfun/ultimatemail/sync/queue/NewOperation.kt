// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Duration

/**
 * A change the user made, to be queued. [payload] depends on [type]: a [FlagChange] encoding for
 * SET_FLAGS, the destination folder path for MOVE, free-form for the rest.
 */
data class NewOperation(
    val accountId: Long,
    val type: OperationType,
    val folderPath: String,
    val uid: Long,
    val payload: String,
    /**
     * How long the operation waits before it may be handed to the server. A move the user can
     * still undo is held for [UNDO_HOLD]: a sync that happens to run in that window must not send
     * it, because a move the server already did cannot be cancelled any more.
     */
    val holdFor: Duration = Duration.ZERO
) {
    companion object {
        /** Longer than the long snackbar (10 s) that offers the undo. */
        val UNDO_HOLD: Duration = Duration.ofSeconds(15)
    }
}
