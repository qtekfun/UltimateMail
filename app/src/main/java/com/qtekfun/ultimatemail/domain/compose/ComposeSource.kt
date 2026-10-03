// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.time.Instant

/**
 * The message a reply or forward starts from, as far as the composer needs it. Built by
 * [ComposeEngine] from Room; tests build it directly.
 *
 * [replyTo] is empty today: the sync does not store the Reply-To header yet (the header model of
 * `MessageHeader` has no field for it), so replies go to [from]. The recipients rule already
 * honours it, so filling it in later needs no change here.
 */
data class ComposeSource(
    val accountId: Long,
    val folderPath: String,
    val uid: Long,
    val messageId: String?,
    val from: MailAddress?,
    val replyTo: List<MailAddress> = emptyList(),
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val subject: String,
    val sentAt: Instant,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** The plain-text body, or null when it has not been downloaded. */
    val bodyText: String?
) {
    override fun toString(): String = "ComposeSource(uid=$uid)"
}
