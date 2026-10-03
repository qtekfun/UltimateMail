// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import java.util.UUID

/**
 * The Message-IDs this app gives the messages it writes.
 *
 * A sent message gets `<uuid@domain>`, fixed when the draft is queued: it is how the message is
 * found again in Sent (SPEC section 5, rule 5). The copy of a draft kept on the server gets
 * `<um-draft.KEY.VERSION@domain>`, where KEY names the draft on every device and VERSION changes
 * with each upload. So the server's Drafts folder says, without any special header, which copies
 * are versions of the same draft and which one is the newest (SPEC section 5, rule 3).
 */
object DraftMessageIds {
    private const val PREFIX = "um-draft."
    private const val FALLBACK_DOMAIN = "localhost"

    /** A new draft key, the same on every device that edits that draft. */
    fun newKey(): String = UUID.randomUUID().toString().replace("-", "")

    /** The Message-ID of a message about to be sent from [fromAddress]. */
    fun forSending(fromAddress: String): String = "<${UUID.randomUUID()}@${domainOf(fromAddress)}>"

    /** The Message-ID of a new server copy of the draft [key]. */
    fun forServerCopy(key: String, fromAddress: String): String =
        "<$PREFIX$key.${UUID.randomUUID().toString().take(
            VERSION_LENGTH
        )}@${domainOf(fromAddress)}>"

    /** The draft key in [messageId] if it is the id of a server copy, else null. */
    fun keyOf(messageId: String?): String? {
        val local = messageId?.trim()?.removePrefix("<")?.substringBefore('@').orEmpty()
        if (!local.startsWith(PREFIX)) return null
        return local.removePrefix(PREFIX).substringBefore('.').takeIf { it.isNotEmpty() }
    }

    private fun domainOf(address: String) =
        address.substringAfter('@', FALLBACK_DOMAIN).ifBlank { FALLBACK_DOMAIN }

    private const val VERSION_LENGTH = 8
}
