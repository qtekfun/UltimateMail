// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/**
 * What identifies a message when its UID cannot be trusted (SPEC section 5, rule 2): the RFC 5322
 * Message-ID and, on Gmail, the X-GM-MSGID. Either may be missing.
 */
data class MessageIdentity(val messageId: String? = null, val gmailMessageId: Long? = null) {
    /** True when nothing stable is known, so only the UID can locate the message. */
    fun isEmpty() = messageId == null && gmailMessageId == null

    /** Two identities name the same message when they share a Message-ID or a Gmail id. */
    fun matches(other: MessageIdentity) = (messageId != null && messageId == other.messageId) ||
        (gmailMessageId != null && gmailMessageId == other.gmailMessageId)
}
