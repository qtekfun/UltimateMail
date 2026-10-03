// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/** How the SMTP attempt ended, as far as the sender could tell. */
enum class SendAttempt {
    /** The server accepted the message. */
    ACCEPTED,

    /** The server refused it outright: it certainly did not take the message. */
    REFUSED,

    /** Timeout, lost connection or unknown error: the server may have taken it anyway. */
    AMBIGUOUS
}

/** What the lookup of the Message-ID in the Sent folder found. */
enum class SentLookup { FOUND, NOT_FOUND, UNAVAILABLE }

sealed interface SendDecision {
    /** The message is out: complete the operation and never send it again. */
    data object Done : SendDecision

    /** It is safe to send again, with the usual backoff. */
    data object Retry : SendDecision

    /** Cannot tell yet: check Sent again later, do not send. */
    data object ConfirmFirst : SendDecision
}

/**
 * Rule 5 for SEND: never send twice after a doubtful answer. After an [SendAttempt.AMBIGUOUS]
 * attempt the caller looks for the message's Message-ID in Sent (the one in the outgoing message,
 * see MailSender) and passes the result. [SentLookup.NOT_FOUND] is only trustworthy once the
 * caller has synced Sent after the attempt; the caller owns that.
 */
object SendResolver {
    fun resolve(attempt: SendAttempt, sent: SentLookup): SendDecision = when (attempt) {
        SendAttempt.ACCEPTED -> SendDecision.Done

        SendAttempt.REFUSED -> SendDecision.Retry

        SendAttempt.AMBIGUOUS -> when (sent) {
            SentLookup.FOUND -> SendDecision.Done
            SentLookup.NOT_FOUND -> SendDecision.Retry
            SentLookup.UNAVAILABLE -> SendDecision.ConfirmFirst
        }
    }
}
