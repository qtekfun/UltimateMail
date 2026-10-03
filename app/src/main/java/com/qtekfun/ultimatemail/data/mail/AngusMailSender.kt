// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import jakarta.mail.Session
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/** Sends over SMTP with Angus Mail (TLS or required STARTTLS, validated certificates). */
class AngusMailSender @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val config: MailClientConfig
) : MailSender {
    // The library throws many unrelated types; this is the one boundary that maps them all.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun send(
        server: MailServer,
        credentials: MailCredentials?,
        message: OutgoingMessage
    ): MailResult<String> = try {
        runInterruptible(io) {
            val mime = MimeMessageBuilder.build(message)
            val session = Session.getInstance(MailProperties.smtp(server, credentials, config))
            session.getTransport(MailProperties.SMTP_PROTOCOL).use { transport ->
                transport.connect(server.host, server.port, credentials?.username, credentials?.secret())
                transport.sendMessage(mime, mime.allRecipients)
            }
            MailResult.Success(checkNotNull(mime.messageID))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MailErrorMapper.map(e)
    }
}
