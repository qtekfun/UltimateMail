// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import jakarta.mail.Session
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import org.eclipse.angus.mail.imap.IMAPStore

/** Opens IMAP sessions with Angus Mail (TLS or required STARTTLS, validated certificates). */
class AngusMailConnector @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val config: MailClientConfig,
    private val extensions: ProviderExtensions
) : MailConnector {
    // The library throws many unrelated types; this is the one boundary that maps them all.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun connect(
        server: MailServer,
        credentials: MailCredentials
    ): MailResult<MailSession> {
        var store: IMAPStore? = null
        return try {
            runInterruptible(io) {
                val session = Session.getInstance(MailProperties.imap(server, credentials, config))
                val opened = session.getStore(MailProperties.IMAP_PROTOCOL) as IMAPStore
                store = opened
                opened.connect(server.host, server.port, credentials.username, credentials.secret())
            }
            MailResult.Success(AngusMailSession(checkNotNull(store), io, extensions))
        } catch (e: CancellationException) {
            closeQuietly(store)
            throw e
        } catch (e: Exception) {
            closeQuietly(store)
            MailErrorMapper.map(e)
        }
    }

    private fun closeQuietly(store: IMAPStore?) {
        runCatching { store?.close() }
    }
}

internal fun MailCredentials.secret(): String = when (this) {
    is MailCredentials.Password -> password
    is MailCredentials.OAuthBearer -> accessToken
}
