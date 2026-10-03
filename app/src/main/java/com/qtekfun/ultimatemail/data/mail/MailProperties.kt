// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import java.util.Properties

/**
 * The Angus Mail session properties. Every connection is TLS or required STARTTLS with
 * certificate and host name checks on; nothing here can turn validation off.
 */
internal object MailProperties {
    /** Angus' "gimap" store behaves as plain IMAP and adds Gmail's extensions, but is TLS-only. */
    private const val GMAIL_IMAP_PROTOCOL = "gimap"
    private const val PLAIN_IMAP_PROTOCOL = "imap"
    const val SMTP_PROTOCOL = "smtp"
    private const val TLS_PROTOCOLS = "TLSv1.2 TLSv1.3"
    private const val XOAUTH2 = "XOAUTH2"
    private const val PASSWORD_MECHANISMS = "PLAIN LOGIN"

    /** The store protocol: with implicit TLS the Gmail-aware one, with STARTTLS the plain one. */
    fun imapProtocol(server: MailServer): String = when (server.security) {
        TransportSecurity.TLS -> GMAIL_IMAP_PROTOCOL
        TransportSecurity.STARTTLS -> PLAIN_IMAP_PROTOCOL
    }

    fun imap(
        server: MailServer,
        credentials: MailCredentials,
        config: MailClientConfig
    ): Properties {
        val prefix = "mail.${imapProtocol(server)}"
        return build(prefix, server, credentials, config).apply {
            // Reading a body must not mark the message as seen.
            put("$prefix.peek", "true")
            put("mail.mime.decodefilename", "true")
        }
    }

    fun smtp(server: MailServer, credentials: MailCredentials?, config: MailClientConfig) =
        build("mail.$SMTP_PROTOCOL", server, credentials, config).apply {
            put("mail.$SMTP_PROTOCOL.auth", (credentials != null).toString())
        }

    private fun build(
        prefix: String,
        server: MailServer,
        credentials: MailCredentials?,
        config: MailClientConfig
    ) = Properties().apply {
        put("$prefix.connectiontimeout", config.connectTimeoutMillis.toString())
        put("$prefix.timeout", config.readTimeoutMillis.toString())
        put("$prefix.writetimeout", config.readTimeoutMillis.toString())
        put("$prefix.ssl.checkserveridentity", "true")
        put("$prefix.ssl.protocols", TLS_PROTOCOLS)
        when (server.security) {
            TransportSecurity.TLS -> put("$prefix.ssl.enable", "true")

            TransportSecurity.STARTTLS -> {
                put("$prefix.starttls.enable", "true")
                put("$prefix.starttls.required", "true")
            }
        }
        // A token must only ever travel as XOAUTH2, never through LOGIN or PLAIN.
        when (credentials) {
            is MailCredentials.OAuthBearer -> put("$prefix.auth.mechanisms", XOAUTH2)
            is MailCredentials.Password -> put("$prefix.auth.mechanisms", PASSWORD_MECHANISMS)
            null -> Unit
        }
        // Only the ssl.* key: ".socketFactory" would make STARTTLS connections start with TLS.
        config.sslSocketFactory?.let { put("$prefix.ssl.socketFactory", it) }
    }
}
