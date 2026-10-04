// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

/** How a new message relates to the one being read. */
enum class ComposeMode { REPLY, REPLY_ALL, FORWARD }

/** A request to write a message from the one being read. */
data class ComposeRequest(
    val accountId: Long,
    val folderPath: String,
    val messageId: Long,
    val mode: ComposeMode
)

/**
 * The seam between reading and the composer (T18). The conversation screen only asks for a
 * compose; whatever is bound to this interface opens the composer (`ui.compose`).
 */
fun interface ComposeLauncher {
    /** Starts composing. Returns false when the request cannot be taken. */
    fun start(request: ComposeRequest): Boolean

    /** Starts a new, empty message from [accountId]. Returns false when it cannot be taken. */
    fun startNew(accountId: Long): Boolean = false
}
