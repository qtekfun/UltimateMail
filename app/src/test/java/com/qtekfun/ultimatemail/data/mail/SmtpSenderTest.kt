// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.OutgoingAttachment
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import jakarta.mail.Multipart
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** SMTP sending against GreenMail over TLS, plus the refusals that must never fall back. */
class SmtpSenderTest {
    private val server = GreenMailServer()
    private val sender = AngusMailSender(Dispatchers.IO, trustingTestConfig())

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.stop()

    private fun message(
        attachments: List<OutgoingAttachment> = emptyList(),
        messageId: String? = null
    ) = OutgoingMessage(
        from = MailAddress(TEST_USER, "Alice"),
        to = listOf(MailAddress(TEST_USER)),
        cc = listOf(MailAddress("carol@example.test")),
        bcc = listOf(MailAddress("dave@example.test")),
        subject = "Grüße from the test",
        text = "plain body",
        html = "<p>html body</p>",
        attachments = attachments,
        inReplyTo = "<parent@example.test>",
        references = listOf("<root@example.test>", "<parent@example.test>"),
        messageId = messageId
    )

    private fun send(
        message: OutgoingMessage,
        credentials: MailCredentials? = server.credentials,
        to: MailServer = server.smtp
    ) = runBlocking { sender.send(to, credentials, message) }

    @Test
    fun `sends with authentication and returns the message id that reached the server`() {
        val result = send(message(messageId = "fixed-id@example.test"))
        assertEquals(MailResult.Success("<fixed-id@example.test>"), result)
        assertTrue(server.greenMail.waitForIncomingEmail(1))
        val received = server.greenMail.receivedMessages.first {
            it.allRecipients.any { a ->
                a.toString() ==
                    TEST_USER
            }
        }
        assertEquals("Grüße from the test", received.subject)
        assertEquals("<fixed-id@example.test>", received.getHeader("Message-ID").first())
        assertEquals("<parent@example.test>", received.getHeader("In-Reply-To").first())
        assertEquals(
            "<root@example.test> <parent@example.test>",
            received.getHeader("References").first()
        )
    }

    @Test
    fun `a generated message id uses the sender domain`() {
        val result = send(message()) as MailResult.Success
        assertTrue(Regex("<[0-9a-f-]{36}@example\\.test>").matches(result.value), result.value)
    }

    @Test
    fun `delivers to to, cc and bcc recipients, without a Bcc header`() {
        send(message())
        assertTrue(server.greenMail.waitForIncomingEmail(3))
        val all = server.greenMail.receivedMessages
        listOf(TEST_USER, "carol@example.test", "dave@example.test").forEach { address ->
            val user = server.greenMail.managers.userManager.getUserByEmail(address)
            assertEquals(
                1,
                server.greenMail.managers.imapHostManager.getInbox(user).messageCount,
                address
            )
        }
        assertTrue(all.none { it.getHeader("Bcc") != null })
    }

    @Test
    fun `sends attachments`() {
        send(
            message(
                listOf(
                    OutgoingAttachment(
                        "report.bin",
                        "application/octet-stream",
                        byteArrayOf(9, 8, 7)
                    )
                )
            )
        )
        assertTrue(server.greenMail.waitForIncomingEmail(1))
        val received = server.greenMail.receivedMessages.first() as MimeMessage
        val mixed = received.content as Multipart
        assertEquals(2, mixed.count)
        assertEquals("report.bin", mixed.getBodyPart(1).fileName)
        assertEquals(listOf<Byte>(9, 8, 7), mixed.getBodyPart(1).inputStream.readBytes().toList())
    }

    @Test
    fun `sends without credentials to a server that takes mail without login`() {
        val result = send(message(), credentials = null)
        assertTrue(result is MailResult.Success, "was $result")
        assertTrue(server.greenMail.waitForIncomingEmail(1))
    }

    @Test
    fun `a wrong password is AuthenticationFailed and nothing is sent`() {
        val result = send(message(), MailCredentials.Password(TEST_USER, "wrong"))
        assertEquals(MailResult.AuthenticationFailed, result)
        assertTrue(server.greenMail.receivedMessages.isEmpty())
    }

    @Test
    fun `a closed port is NetworkUnavailable`() {
        val result = send(message(), to = MailServer(TEST_HOST, freePort(), TransportSecurity.TLS))
        assertEquals(MailResult.NetworkUnavailable, result)
    }

    @Test
    fun `STARTTLS is required, so a server without it is refused and gets no mail or password`() {
        val plain = GreenMail(ServerSetup(freePort(), TEST_HOST, ServerSetup.PROTOCOL_SMTP))
        plain.start()
        try {
            val port = plain.smtp.port
            val result =
                send(message(), to = MailServer(TEST_HOST, port, TransportSecurity.STARTTLS))
            assertEquals(MailResult.Unsupported("STARTTLS"), result)
            assertTrue(plain.receivedMessages.isEmpty())
        } finally {
            plain.stop()
        }
    }

    @Test
    fun `a message without a recipient is a failure, not a crash`() {
        val empty =
            OutgoingMessage(
                from = MailAddress(TEST_USER),
                to = emptyList(),
                subject = "s",
                text = "t"
            )
        val result = send(empty)
        assertTrue(result is MailResult.Failure, "was $result")
    }
}
