// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

/**
 * The outcome of a mail-server operation. The gateways never throw to their callers: every
 * failure is one of the [Failure] subtypes, so the sync queue can decide whether to retry.
 * Failures carry no server text, because it can contain addresses or subjects.
 */
sealed interface MailResult<out T> {
    data class Success<out T>(val value: T) : MailResult<T>

    sealed interface Failure : MailResult<Nothing>

    /** The server refused the credentials or the token (RF-01: ask the user to sign in again). */
    data object AuthenticationFailed : Failure

    /** The server could not be reached, or the connection dropped. Worth retrying later. */
    data object NetworkUnavailable : Failure

    /** The server accepted the connection but did not answer in time. Worth retrying later. */
    data object Timeout : Failure

    /** The server certificate was not trusted or did not match the host. Never retried blindly. */
    data object CertificateRejected : Failure

    /**
     * The server answered NO or BAD (IMAP) or a 4xx/5xx reply (SMTP) to a command.
     * [permanent] is false for replies the server marks as transient (for example 4xx).
     */
    data class ServerRejected(
        val kind: RejectionKind,
        val permanent: Boolean,
        val code: Int? = null
    ) : Failure

    /** The folder, message or attachment part asked for is not on the server any more. */
    data object NotFound : Failure

    /** The server lacks an extension the call needs (for example Gmail labels elsewhere). */
    data class Unsupported(val feature: String) : Failure

    /** The server spoke something this client could not understand. */
    data object Protocol : Failure

    /** Anything else; a bug or an unforeseen error. */
    data object Unknown : Failure
}

/** The kind of refusal in [MailResult.ServerRejected]. */
enum class RejectionKind { NO, BAD, SMTP }

fun <T, R> MailResult<T>.map(transform: (T) -> R): MailResult<R> = when (this) {
    is MailResult.Success -> MailResult.Success(transform(value))
    is MailResult.Failure -> this
}

fun <T> MailResult<T>.getOrNull(): T? = (this as? MailResult.Success)?.value

/** True for failures that a later retry can fix, as opposed to ones that need the user. */
val MailResult.Failure.isRetryable: Boolean
    get() = when (this) {
        MailResult.NetworkUnavailable, MailResult.Timeout -> true

        is MailResult.ServerRejected -> !permanent

        MailResult.AuthenticationFailed,
        MailResult.CertificateRejected,
        MailResult.NotFound,
        is MailResult.Unsupported,
        MailResult.Protocol,
        MailResult.Unknown -> false
    }
