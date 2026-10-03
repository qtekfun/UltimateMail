// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

/** Sends messages over SMTP (RF-07). */
interface MailSender {
    /**
     * Sends [message] and returns its Message-ID. [credentials] may be null for a server that
     * takes mail without login.
     *
     * Careful: [MailResult.Timeout], [MailResult.NetworkUnavailable] and [MailResult.Unknown] can
     * also mean the server took the message before the answer was lost. Look in the Sent folder
     * for the Message-ID (the one in [OutgoingMessage.messageId], or the one generated) before
     * sending again (SPEC section 5, rule 5).
     */
    suspend fun send(
        server: MailServer,
        credentials: MailCredentials?,
        message: OutgoingMessage
    ): MailResult<String>
}
