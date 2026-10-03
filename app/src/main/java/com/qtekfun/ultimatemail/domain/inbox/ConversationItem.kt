// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import java.time.Instant

/**
 * One row of a conversation list: the newest message of the conversation plus the counters of
 * the whole conversation. The UI never sees Room entities (RF-03).
 */
data class ConversationItem(
    val accountId: Long,
    val folderPath: String,
    val threadId: String,
    /** Id of the newest message, the one a tap opens. */
    val latestMessageId: Long,
    val senderName: String,
    val senderAddress: String,
    val subject: String,
    val snippet: String,
    val sentAt: Instant,
    val messageCount: Int,
    val unreadCount: Int,
    val flagged: Boolean,
    val hasAttachments: Boolean,
    /** Raw Gmail labels; turn them into chips with [LabelPresentation]. */
    val labels: List<String>,
    /** A local change of this conversation is waiting to be sent (RF-10). */
    val pendingSync: Boolean
) {
    /** Stable list key: it survives new messages arriving in the conversation. */
    val key: String get() = "$accountId|$folderPath|$threadId"

    val unread: Boolean get() = unreadCount > 0

    /** The name to show for the sender: the display name, or the address when there is none. */
    val sender: String get() = senderName.ifBlank { senderAddress }
}
