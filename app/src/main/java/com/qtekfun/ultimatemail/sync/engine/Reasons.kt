// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.mail.UidRange
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome

/** The reason codes the executor stores as the last error of an operation (never content). */
internal object Reasons {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val AUTH_REQUIRED = "auth_required"
    const val CERTIFICATE = "certificate"
    const val SERVER_REJECTED = "server_rejected"
    const val SERVER_BUSY = "server_busy"
    const val NOT_FOUND = "not_found"
    const val UNSUPPORTED = "unsupported"
    const val UNEXPECTED = "unexpected"
    const val FOLDER_RESET = "folder_reset"
    const val CONFIRM_SENT = "confirm_sent"
    const val NOT_SYNCED = "not_synced"
    const val BAD_PAYLOAD = "bad_payload"
    const val NO_ACCOUNT = "no_account"
    const val ATTACHMENT_MISSING = "attachment_missing"
}

/** How many of the newest messages of Sent or Drafts are searched for a Message-ID. */
internal const val RECENT_MESSAGES = 50L

/** Drafts folders are small and a draft may sit far from the end, so they are searched deeper. */
internal const val RECENT_DRAFTS = 200L

/** What an IMAP or SMTP failure means for the operation that met it. */
internal fun failureOutcome(failure: MailResult.Failure): OperationOutcome = when (failure) {
    MailResult.NetworkUnavailable -> OperationOutcome.RetryLater(Reasons.NETWORK)

    MailResult.Timeout -> OperationOutcome.RetryLater(Reasons.TIMEOUT)

    // The login may only need the user; the operation waits and the account says so.
    MailResult.AuthenticationFailed -> OperationOutcome.RetryLater(Reasons.AUTH_REQUIRED)

    MailResult.CertificateRejected -> OperationOutcome.Rejected(Reasons.CERTIFICATE)

    is MailResult.ServerRejected ->
        if (failure.permanent) {
            OperationOutcome.Rejected(Reasons.SERVER_REJECTED)
        } else {
            OperationOutcome.RetryLater(Reasons.SERVER_BUSY)
        }

    MailResult.NotFound -> OperationOutcome.Rejected(Reasons.NOT_FOUND)

    is MailResult.Unsupported -> OperationOutcome.Rejected(Reasons.UNSUPPORTED)

    MailResult.Protocol, MailResult.Unknown -> OperationOutcome.RetryLater(Reasons.UNEXPECTED)
}

/** The newest [count] headers of [folder] (fewer if it is smaller). */
// Each failure leaves early; guard clauses keep the normal path flat.
@Suppress("ReturnCount")
internal suspend fun MailSession.newestHeaders(
    folder: String,
    count: Long
): MailResult<List<MessageHeader>> {
    val status = when (val result = folderStatus(folder)) {
        is MailResult.Success -> result.value
        is MailResult.Failure -> return result
    }
    val top = status.uidNext - 1
    if (top < 1) return MailResult.Success(emptyList())
    return fetchHeaders(folder, UidRange(maxOf(1, top - count + 1), top))
}

/** Whether [folder] holds [messageId] among its newest messages. */
internal suspend fun MailSession.holdsMessageId(
    folder: String,
    messageId: String?
): MailResult<Boolean> = when (val headers = newestHeaders(folder, RECENT_MESSAGES)) {
    is MailResult.Success -> MailResult.Success(
        headers.value.any {
            it.messageId.sameMessageId(messageId)
        }
    )

    is MailResult.Failure -> headers
}

internal fun String?.sameMessageId(other: String?) =
    this != null && other != null && trim().trim('<', '>') == other.trim().trim('<', '>')
