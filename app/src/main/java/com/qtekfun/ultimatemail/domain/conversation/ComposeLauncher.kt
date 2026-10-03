// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import javax.inject.Inject

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
 * compose; whatever is bound to this interface opens the composer.
 *
 * TODO(T18): bind the real implementation (open the composer with the quoted message, the
 * recipients for [ComposeMode.REPLY_ALL] and the signature) in `ComposeModule` instead of
 * [ComposeUnavailable], and navigate to the composer screen.
 */
fun interface ComposeLauncher {
    /** Starts composing. Returns false when composing is not available (yet). */
    fun start(request: ComposeRequest): Boolean
}

/** The placeholder until T18: composing is not available, so the screen says "coming soon". */
class ComposeUnavailable @Inject constructor() : ComposeLauncher {
    override fun start(request: ComposeRequest): Boolean = false
}
