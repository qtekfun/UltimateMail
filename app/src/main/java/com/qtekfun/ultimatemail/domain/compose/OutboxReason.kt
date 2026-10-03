// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

/**
 * Why a message in the outbox is waiting or failed, grouped for the texts the user reads. The
 * sync stores short reason codes (never content); this is their one translation, so a code the
 * UI does not know still ends in a sensible text ([OTHER]).
 */
enum class OutboxReason {
    /** No connection to the server. */
    NETWORK,

    /** The server did not answer in time. */
    TIMEOUT,

    /** The password or the sign-in has to be renewed. */
    AUTH_REQUIRED,

    /** The server's certificate is not trusted. */
    CERTIFICATE,

    /** The server refused the message for good. */
    SERVER_REJECTED,

    /** The server is busy or refused for now. */
    SERVER_BUSY,

    /** The server may have taken it; Sent has to be checked. */
    CONFIRM_SENT,

    /** A file attached to the message is gone from the device. */
    ATTACHMENT_MISSING,

    /** The message cannot be prepared (account gone, damaged data). */
    INTERNAL,

    OTHER;

    companion object {
        /** The reason for a stored [code]; null (nothing went wrong yet) is [OTHER]. */
        fun of(code: String?): OutboxReason = when (code) {
            "network" -> NETWORK
            "timeout" -> TIMEOUT
            "auth_required" -> AUTH_REQUIRED
            "certificate" -> CERTIFICATE
            "server_rejected" -> SERVER_REJECTED
            "server_busy" -> SERVER_BUSY
            "confirm_sent" -> CONFIRM_SENT
            "attachment_missing" -> ATTACHMENT_MISSING
            "bad_payload", "no_account", "no_operation" -> INTERNAL
            else -> OTHER
        }
    }
}
