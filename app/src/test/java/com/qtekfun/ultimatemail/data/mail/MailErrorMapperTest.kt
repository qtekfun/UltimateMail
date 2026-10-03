// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.mail.StoreClosedException
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.ConnectionException
import org.eclipse.angus.mail.iap.ProtocolException
import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MailErrorMapperTest {
    private fun map(error: Throwable) = MailErrorMapper.map(error)

    @Test
    fun `authentication failure wins even inside wrappers`() {
        assertEquals(
            MailResult.AuthenticationFailed,
            map(MessagingException("wrapped", AuthenticationFailedException("bad")))
        )
    }

    @Test
    fun `timeouts`() {
        assertEquals(MailResult.Timeout, map(SocketTimeoutException("Read timed out")))
        assertEquals(MailResult.Timeout, map(MessagingException("x", SocketTimeoutException())))
        assertEquals(MailResult.Timeout, map(InterruptedIOException()))
        // IMAP reports a read timeout only as the text of a BYE response.
        assertEquals(
            MailResult.Timeout,
            map(StoreClosedException(null, "* BYE Jakarta Mail Exception: Read timed out"))
        )
    }

    @Test
    fun `certificate problems are not network problems`() {
        assertEquals(
            MailResult.CertificateRejected,
            map(
                SSLHandshakeException("x").apply {
                    initCause(CertificateException())
                }
            )
        )
        assertEquals(MailResult.CertificateRejected, map(SSLPeerUnverifiedException("x")))
        assertEquals(
            MailResult.CertificateRejected,
            map(MessagingException("Server is not trusted: mail.example.test"))
        )
    }

    @Test
    fun `a server without STARTTLS is unsupported`() {
        assertEquals(
            MailResult.Unsupported("STARTTLS"),
            map(MessagingException("STARTTLS is required but host does not support STARTTLS"))
        )
        assertEquals(
            MailResult.Unsupported("STARTTLS"),
            map(
                MessagingException(
                    "x",
                    ProtocolException("STARTTLS required but not supported by server")
                )
            )
        )
    }

    @Test
    fun `IMAP NO and BAD`() {
        assertEquals(
            MailResult.ServerRejected(RejectionKind.NO, permanent = true),
            map(
                MessagingException(
                    "x",
                    CommandFailedException(Response("A1 NO [NONEXISTENT] gone"))
                )
            )
        )
        assertEquals(
            MailResult.ServerRejected(RejectionKind.NO, permanent = false),
            map(CommandFailedException(Response("A1 NO [UNAVAILABLE] busy")))
        )
        assertEquals(
            MailResult.ServerRejected(RejectionKind.NO, permanent = false),
            map(CommandFailedException(Response("A1 NO [INUSE] locked")))
        )
        assertEquals(
            MailResult.ServerRejected(RejectionKind.BAD, permanent = true),
            map(BadCommandException(Response("A1 BAD what")))
        )
    }

    @Test
    fun `SMTP replies are transient below 500`() {
        val sendFailed =
            SMTPSendFailedException("RCPT", 450, "mailbox busy", null, null, null, null)
        assertEquals(
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = false, code = 450),
            map(sendFailed)
        )
        val permanent = SMTPSendFailedException("DATA", 554, "rejected", null, null, null, null)
        assertEquals(
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = true, code = 554),
            map(permanent)
        )
        val address = SMTPAddressFailedException(null, "RCPT", 550, "no such user")
        assertEquals(
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = true, code = 550),
            map(address)
        )
        val sender = SMTPSenderFailedException(null, "MAIL", 421, "closing")
        assertEquals(
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = false, code = 421),
            map(sender)
        )
    }

    @Test
    fun `missing folder and other protocol errors`() {
        assertEquals(MailResult.NotFound, map(FolderNotFoundException(null, "x")))
        assertEquals(MailResult.Protocol, map(ProtocolException("garbage")))
    }

    @Test
    fun `dropped or unreachable connections`() {
        listOf(
            ConnectException(),
            UnknownHostException(),
            NoRouteToHostException(),
            EOFException(),
            SocketException("Connection reset"),
            StoreClosedException(null),
            FolderClosedException(null),
            ConnectionException("lost"),
            MessagingException("x", SocketException())
        ).forEach { assertEquals(MailResult.NetworkUnavailable, map(it), it.toString()) }
    }

    @Test
    fun `TLS that fails for another reason is a protocol failure`() {
        assertEquals(
            MailResult.Protocol,
            map(SSLException("Unsupported or unrecognized SSL message"))
        )
    }

    @Test
    fun `anything else is Unknown`() {
        assertEquals(MailResult.Unknown, map(IllegalStateException("bug")))
        assertEquals(MailResult.Unknown, map(MessagingException("odd")))
    }

    @Test
    fun `a cause chain that loops terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(MailResult.Unknown, map(a))
    }
}
