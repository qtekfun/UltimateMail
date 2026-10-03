// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import javax.inject.Inject

/**
 * Tests an account by opening a real IMAP session with its settings and closing it again. Only
 * the incoming server is checked: the SMTP login is exercised by the first send.
 */
class MailAccountConnectionTester @Inject constructor(private val connector: MailConnector) :
    AccountConnectionTester {
    override suspend fun test(input: AccountInput): ConnectionTestResult {
        val credentials = credentialsOf(input)
            ?: return ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED)
        val server = MailServer(input.imap.host, input.imap.port, input.imap.security.toTransport())
        return when (val result = connector.connect(server, credentials)) {
            is MailResult.Success -> {
                result.value.close()
                ConnectionTestResult.Success
            }

            is MailResult.Failure -> ConnectionTestResult.Failure(result.toConnectionFailure())
        }
    }

    private fun credentialsOf(input: AccountInput): MailCredentials? = when (input.authType) {
        AuthType.PASSWORD -> input.credentials.password?.let {
            MailCredentials.Password(input.username.trim(), it)
        }

        AuthType.OAUTH_GOOGLE, AuthType.OAUTH_MICROSOFT -> input.credentials.oauth?.let {
            MailCredentials.OAuthBearer(input.username.trim(), it.accessToken)
        }
    }

    private fun ConnectionSecurity.toTransport() = when (this) {
        ConnectionSecurity.TLS -> TransportSecurity.TLS
        ConnectionSecurity.STARTTLS -> TransportSecurity.STARTTLS
    }

    private fun MailResult.Failure.toConnectionFailure(): ConnectionFailure = when (this) {
        MailResult.AuthenticationFailed -> ConnectionFailure.AUTHENTICATION_FAILED

        MailResult.NetworkUnavailable -> ConnectionFailure.HOST_UNREACHABLE

        MailResult.Timeout -> ConnectionFailure.TIMEOUT

        // A server without the STARTTLS we require is a transport security problem too.
        MailResult.CertificateRejected, is MailResult.Unsupported -> ConnectionFailure.TLS_ERROR

        is MailResult.ServerRejected,
        MailResult.NotFound,
        MailResult.Protocol,
        MailResult.Unknown -> ConnectionFailure.UNKNOWN
    }
}
