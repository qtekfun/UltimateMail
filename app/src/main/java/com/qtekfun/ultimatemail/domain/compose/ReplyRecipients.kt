// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.util.Locale

/** The To and Cc of a reply. */
data class ReplyRecipients(val to: List<MailAddress>, val cc: List<MailAddress>) {
    override fun toString(): String = "ReplyRecipients(to=${to.size}, cc=${cc.size})"
}

/**
 * Who a reply goes to (RF-07).
 *
 * - Reply: to the Reply-To of the message, else its sender. Answering a message the user sent
 *   (a reply to their own mail from Sent) goes to the people it was sent to instead.
 * - Reply all: the same, plus everyone in To and Cc.
 * - In both, the user's own addresses are dropped, and so are duplicates (case-insensitive, by
 *   address; the first spelling and display name win). Someone in To is not repeated in Cc.
 * - Forward and new messages start with no recipients.
 */
object ReplyRecipientsRule {
    fun of(kind: DraftKind, source: ComposeSource, own: Set<String>): ReplyRecipients {
        if (kind != DraftKind.REPLY && kind != DraftKind.REPLY_ALL) {
            return ReplyRecipients(emptyList(), emptyList())
        }
        val mine = own.map(::key).toSet()
        val sentByUser = source.from?.let { key(it.address) in mine } == true
        val primary = when {
            source.replyTo.isNotEmpty() -> source.replyTo
            sentByUser -> source.to
            else -> listOfNotNull(source.from)
        }
        val to = mutableListOf<MailAddress>()
        val seen = mine.toMutableSet()
        fun addTo(list: MutableList<MailAddress>, addresses: List<MailAddress>) {
            addresses.forEach { if (seen.add(key(it.address))) list += it }
        }
        addTo(to, primary)
        val cc = mutableListOf<MailAddress>()
        if (kind == DraftKind.REPLY_ALL) {
            addTo(to, source.to)
            addTo(cc, source.cc)
        }
        return ReplyRecipients(to, cc)
    }

    private fun key(address: String) = address.trim().lowercase(Locale.ROOT)
}
