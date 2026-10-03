// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The production connection settings against a server whose certificate nobody trusts. */
class ConnectionSecurityTest {
    private val server = GreenMailServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.stop()

    @Test
    fun `with the system trust store an unknown certificate is CertificateRejected on IMAP`() {
        val connector = AngusMailConnector(Dispatchers.IO, MailClientConfig(), GmailExtensions())
        val result = runBlocking { connector.connect(server.imap, server.credentials) }
        assertEquals(MailResult.CertificateRejected, result)
    }

    @Test
    fun `with the system trust store an unknown certificate is CertificateRejected on SMTP`() {
        val sender = AngusMailSender(Dispatchers.IO, MailClientConfig())
        val message = com.qtekfun.ultimatemail.domain.mail.OutgoingMessage(
            from = com.qtekfun.ultimatemail.domain.mail.MailAddress(TEST_USER),
            to = listOf(com.qtekfun.ultimatemail.domain.mail.MailAddress(TEST_USER)),
            subject = "s",
            text = "t"
        )
        val result = runBlocking { sender.send(server.smtp, server.credentials, message) }
        assertEquals(MailResult.CertificateRejected, result)
        assertTrue(server.greenMail.receivedMessages.isEmpty())
    }

    @Test
    fun `a certificate for another host name is rejected even when the chain is trusted`() {
        // The test certificate is for 127.0.0.1; reach the same server as "localhost".
        val connector = AngusMailConnector(Dispatchers.IO, trustingTestConfig(), GmailExtensions())
        val wrongName = MailServer("localhost", server.imapPort, TransportSecurity.TLS)
        assertEquals(
            MailResult.CertificateRejected,
            runBlocking {
                connector.connect(wrongName, server.credentials)
            }
        )
    }

    @Test
    fun `STARTTLS is required on IMAP too`() {
        val plain = GreenMail(ServerSetup(freePort(), TEST_HOST, ServerSetup.PROTOCOL_IMAP))
        plain.start()
        try {
            plain.setUser(TEST_USER, TEST_USER, TEST_PASSWORD)
            val connector =
                AngusMailConnector(Dispatchers.IO, trustingTestConfig(), GmailExtensions())
            val result = runBlocking {
                connector.connect(
                    MailServer(TEST_HOST, plain.imap.port, TransportSecurity.STARTTLS),
                    server.credentials
                )
            }
            assertEquals(MailResult.Unsupported("STARTTLS"), result)
        } finally {
            plain.stop()
        }
    }

    @Test
    fun `GreenMail is not Gmail, so the Gmail extensions report themselves absent`() {
        val properties = MailProperties.imap(server.imap, server.credentials, trustingTestConfig())
        val store = Session.getInstance(
            properties
        ).getStore(MailProperties.imapProtocol(server.imap)) as IMAPStore
        store.connect(TEST_HOST, server.imapPort, TEST_USER, TEST_PASSWORD)
        try {
            assertFalse(GmailExtensions().isAvailable(store))
        } finally {
            store.close()
        }
    }
}
