// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The security-relevant session properties: TLS, validation, timeouts, how a token travels. */
class MailPropertiesTest {
    private val password = MailCredentials.Password("u", "p")
    private val token = MailCredentials.OAuthBearer("u", "tok")
    private val tls = MailServer("h", 993, TransportSecurity.TLS)
    private val starttls = MailServer("h", 143, TransportSecurity.STARTTLS)
    private val config = MailClientConfig(connectTimeoutMillis = 1234, readTimeoutMillis = 5678)

    @Test
    fun `TLS turns on SSL and checks the server identity`() {
        val p = MailProperties.imap(tls, password, config)
        assertEquals("true", p["mail.gimap.ssl.enable"])
        assertEquals("true", p["mail.gimap.ssl.checkserveridentity"])
        assertEquals("TLSv1.2 TLSv1.3", p["mail.gimap.ssl.protocols"])
        assertNull(p["mail.gimap.starttls.enable"])
    }

    @Test
    fun `the store protocol follows the transport security`() {
        assertEquals("gimap", MailProperties.imapProtocol(tls))
        assertEquals("imap", MailProperties.imapProtocol(starttls))
        assertEquals(
            "true",
            MailProperties.imap(starttls, password, config)["mail.imap.starttls.required"]
        )
        assertNull(MailProperties.imap(starttls, password, config)["mail.gimap.starttls.required"])
    }

    @Test
    fun `STARTTLS is required, never optional`() {
        val p = MailProperties.smtp(starttls, password, config)
        assertEquals("true", p["mail.smtp.starttls.enable"])
        assertEquals("true", p["mail.smtp.starttls.required"])
        assertEquals("true", p["mail.smtp.ssl.checkserveridentity"])
        assertNull(p["mail.smtp.ssl.enable"])
    }

    @Test
    fun `nothing disables certificate validation`() {
        listOf(
            MailProperties.imap(tls, password, config),
            MailProperties.imap(starttls, token, config),
            MailProperties.smtp(tls, null, config)
        ).forEach { p ->
            assertFalse(
                p.keys.any {
                    it.toString().endsWith(".ssl.trust")
                },
                "ssl.trust must stay unset"
            )
            assertFalse(p.values.any { it == "*" })
            assertNull(p["mail.gimap.ssl.socketFactory"])
            assertNull(p["mail.smtp.ssl.socketFactory"])
        }
    }

    @Test
    fun `timeouts are set for connect, read and write`() {
        val p = MailProperties.imap(tls, password, config)
        assertEquals("1234", p["mail.gimap.connectiontimeout"])
        assertEquals("5678", p["mail.gimap.timeout"])
        assertEquals("5678", p["mail.gimap.writetimeout"])
        val s = MailProperties.smtp(tls, password, config)
        assertEquals("1234", s["mail.smtp.connectiontimeout"])
        assertEquals("5678", s["mail.smtp.timeout"])
    }

    @Test
    fun `an OAuth token only travels as XOAUTH2`() {
        assertEquals(
            "XOAUTH2",
            MailProperties.imap(tls, token, config)["mail.gimap.auth.mechanisms"]
        )
        assertEquals(
            "XOAUTH2",
            MailProperties.smtp(tls, token, config)["mail.smtp.auth.mechanisms"]
        )
        assertEquals(
            "PLAIN LOGIN",
            MailProperties.imap(tls, password, config)["mail.gimap.auth.mechanisms"]
        )
    }

    @Test
    fun `smtp authenticates only when there are credentials`() {
        assertEquals("true", MailProperties.smtp(tls, password, config)["mail.smtp.auth"])
        assertEquals("false", MailProperties.smtp(tls, null, config)["mail.smtp.auth"])
    }

    @Test
    fun `reading a message does not mark it seen`() {
        assertEquals("true", MailProperties.imap(tls, password, config)["mail.gimap.peek"])
    }

    @Test
    fun `a custom socket factory is passed on only when configured`() {
        val factory = javax.net.ssl.SSLContext.getDefault().socketFactory
        val p = MailProperties.imap(tls, password, config.copy(sslSocketFactory = factory))
        assertEquals(factory, p["mail.gimap.ssl.socketFactory"])
        assertNull(p["mail.gimap.socketFactory"], "that key would make STARTTLS start with TLS")
    }

    @Test
    fun `timeouts must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            MailClientConfig(connectTimeoutMillis = 0)
        }
    }

    @Test
    fun `secrets never show in toString`() {
        assertEquals("OAuthBearer(REDACTED)", token.toString())
        assertEquals("Password(REDACTED)", password.toString())
    }
}
