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
import javax.net.ssl.SSLPeerUnverifiedException
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.ConnectionException
import org.eclipse.angus.mail.iap.ProtocolException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException

/**
 * Turns whatever the mail library throws into a [MailResult.Failure]. The library wraps socket
 * errors in its own exceptions at several levels, so the whole cause chain is inspected; the
 * most specific cause wins (a certificate problem beats the generic wrapper around it).
 */
internal object MailErrorMapper {
    private const val SMTP_TRANSIENT_FIRST = 400
    private const val SMTP_TRANSIENT_LAST = 499
    private val transientImapCodes = Regex("""\[(UNAVAILABLE|INUSE|TRYAGAIN|SERVERBUG)]""")

    fun map(error: Throwable): MailResult.Failure {
        val chain = generateSequence(error) { it.cause }.take(MAX_CHAIN).toList()
        return mapAuthentication(chain)
            ?: mapMissingStartTls(chain)
            ?: mapCertificate(chain)
            ?: mapTimeout(chain)
            ?: mapRejection(chain)
            ?: mapConnection(chain)
            ?: MailResult.Unknown
    }

    private fun mapAuthentication(chain: List<Throwable>): MailResult.Failure? =
        MailResult.AuthenticationFailed.takeIf { chain.any { it is AuthenticationFailedException } }

    /** STARTTLS is required, so a server without it is refused rather than used in clear text. */
    private fun mapMissingStartTls(chain: List<Throwable>): MailResult.Failure? =
        MailResult.Unsupported("STARTTLS").takeIf {
            chain.any { it is MessagingException && it.message.orEmpty().startsWith(STARTTLS_REQUIRED) }
        }

    private fun mapCertificate(chain: List<Throwable>): MailResult.Failure? = when {
        chain.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
            MailResult.CertificateRejected
        // The library's own host name check (mail.*.ssl.checkserveridentity).
        chain.any { it is MessagingException && it.message.orEmpty().startsWith(UNTRUSTED_SERVER) } ->
            MailResult.CertificateRejected
        else -> null
    }

    private fun mapTimeout(chain: List<Throwable>): MailResult.Failure? = when {
        chain.any { it is SocketTimeoutException } -> MailResult.Timeout
        // A read timeout inside an IMAP command surfaces only as text in a BYE response.
        chain.any { TIMED_OUT in it.message.orEmpty() } -> MailResult.Timeout
        chain.any { it is InterruptedIOException } -> MailResult.Timeout
        else -> null
    }

    private fun mapRejection(chain: List<Throwable>): MailResult.Failure? {
        for (cause in chain) {
            val rejection = when (cause) {
                is BadCommandException -> MailResult.ServerRejected(RejectionKind.BAD, permanent = true)
                is CommandFailedException ->
                    MailResult.ServerRejected(RejectionKind.NO, permanent = !cause.isTransient())
                is SMTPSendFailedException -> smtp(cause.returnCode)
                is SMTPAddressFailedException -> smtp(cause.returnCode)
                is SMTPSenderFailedException -> smtp(cause.returnCode)
                is FolderNotFoundException -> MailResult.NotFound
                is ConnectionException -> MailResult.NetworkUnavailable
                is ProtocolException -> MailResult.Protocol
                else -> null
            }
            if (rejection != null) return rejection
        }
        return null
    }

    private fun mapConnection(chain: List<Throwable>): MailResult.Failure? = when {
        chain.any {
            it is ConnectException ||
                it is UnknownHostException ||
                it is NoRouteToHostException ||
                it is EOFException ||
                it is StoreClosedException ||
                it is FolderClosedException
        } -> MailResult.NetworkUnavailable
        // Not a certificate problem, so TLS itself failed: the server speaks something else.
        chain.any { it is SSLException } -> MailResult.Protocol
        chain.any { it is SocketException } -> MailResult.NetworkUnavailable
        else -> null
    }

    private fun smtp(code: Int) = MailResult.ServerRejected(
        kind = RejectionKind.SMTP,
        permanent = code !in SMTP_TRANSIENT_FIRST..SMTP_TRANSIENT_LAST,
        code = code
    )

    private fun CommandFailedException.isTransient(): Boolean =
        transientImapCodes.containsMatchIn(response?.toString().orEmpty())

    private const val MAX_CHAIN = 16
    private const val TIMED_OUT = "timed out"
    private const val STARTTLS_REQUIRED = "STARTTLS is required"
    private const val UNTRUSTED_SERVER = "Server is not trusted"
}
