// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import javax.inject.Inject

/**
 * Guesses server settings from the domain of an e-mail address, with a small built-in table of
 * well-known providers. Anything else gets the usual imap.<domain>/smtp.<domain> guess.
 */
class ServerAutodetector @Inject constructor() {
    fun detect(email: String): ServerSuggestion? {
        val domain = AccountValidator.domainOf(email) ?: return null
        return when (domain) {
            in GOOGLE_DOMAINS -> provider(AuthType.OAUTH_GOOGLE, "imap.gmail.com", "smtp.gmail.com")

            in MICROSOFT_DOMAINS ->
                provider(AuthType.OAUTH_MICROSOFT, "outlook.office365.com", "smtp.office365.com")

            else -> provider(AuthType.PASSWORD, "imap.$domain", "smtp.$domain")
        }
    }

    private fun provider(authType: AuthType, imapHost: String, smtpHost: String) = ServerSuggestion(
        authType = authType,
        imap = ServerEndpoint(imapHost, IMAP_TLS_PORT, ConnectionSecurity.TLS),
        smtp = ServerEndpoint(smtpHost, SMTP_STARTTLS_PORT, ConnectionSecurity.STARTTLS)
    )

    private companion object {
        const val IMAP_TLS_PORT = 993
        const val SMTP_STARTTLS_PORT = 587
        val GOOGLE_DOMAINS = setOf("gmail.com", "googlemail.com")
        val MICROSOFT_DOMAINS = setOf("outlook.com", "hotmail.com", "live.com", "office365.com")
    }
}
