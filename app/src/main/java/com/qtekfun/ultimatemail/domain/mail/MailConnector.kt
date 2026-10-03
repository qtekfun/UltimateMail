// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

/** Opens IMAP sessions. */
interface MailConnector {
    /** Connects and authenticates; the caller closes the session. */
    suspend fun connect(server: MailServer, credentials: MailCredentials): MailResult<MailSession>
}
