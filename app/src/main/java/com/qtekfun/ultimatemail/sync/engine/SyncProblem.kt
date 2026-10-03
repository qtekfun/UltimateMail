// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailResult

/** Why a sync of an account did not finish; a reason code for the UI, never server text. */
enum class SyncProblem(val retryable: Boolean) {
    NETWORK(retryable = true),
    TIMEOUT(retryable = true),
    CERTIFICATE(retryable = false),
    SERVER(retryable = false),
    PROTOCOL(retryable = false),
    UNKNOWN(retryable = false)
}

internal fun MailResult.Failure.toProblem(): SyncProblem = when (this) {
    MailResult.NetworkUnavailable -> SyncProblem.NETWORK

    MailResult.Timeout -> SyncProblem.TIMEOUT

    MailResult.CertificateRejected -> SyncProblem.CERTIFICATE

    is MailResult.ServerRejected,
    MailResult.NotFound,
    is MailResult.Unsupported -> SyncProblem.SERVER

    MailResult.Protocol -> SyncProblem.PROTOCOL

    // Authentication is handled before it gets here; it is never reported as a plain problem.
    MailResult.AuthenticationFailed, MailResult.Unknown -> SyncProblem.UNKNOWN
}
