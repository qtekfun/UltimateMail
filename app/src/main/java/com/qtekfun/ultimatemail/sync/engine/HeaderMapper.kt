// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.thread.MessageRef
import com.qtekfun.ultimatemail.domain.thread.ThreadMessage
import java.time.Instant

/** A header without a Date counts as received now, so it neither vanishes nor sorts to 1970. */
internal fun MessageHeader.sentAt(now: Instant): Instant = date ?: now

internal fun MessageHeader.toThreadMessage(
    accountId: Long,
    folderPath: String,
    now: Instant
) = ThreadMessage(
    ref = MessageRef(accountId, folderPath, uid),
    messageId = messageId,
    inReplyTo = inReplyTo,
    references = references,
    subject = subject,
    sentAt = sentAt(now),
    gmailThreadId = gmail?.threadId?.toString()
)

internal fun MessageHeader.toEntity(
    accountId: Long,
    folderPath: String,
    threadId: String,
    now: Instant
) = MessageEntity(
    accountId = accountId,
    folderPath = folderPath,
    uid = uid,
    messageId = messageId,
    gmailMessageId = gmail?.messageId,
    threadId = threadId,
    subject = subject.orEmpty(),
    senderName = from?.name.orEmpty(),
    senderAddress = from?.address.orEmpty(),
    toAddresses = to.map { it.address },
    ccAddresses = cc.map { it.address },
    sentAt = sentAt(now),
    seen = flags.seen,
    flagged = flags.flagged,
    answered = flags.answered,
    draft = flags.draft,
    hasAttachments = hasAttachments,
    size = size,
    inReplyTo = inReplyTo,
    referenceIds = references,
    labels = gmail?.labels.orEmpty()
)
