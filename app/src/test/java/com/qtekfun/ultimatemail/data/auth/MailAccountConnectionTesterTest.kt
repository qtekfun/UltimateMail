// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.auth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint
import com.qtekfun.ultimatemail.domain.account.accountInput
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MailAccountConnectionTesterTest {
    private val connector = mockk<MailConnector>()
    private val session = mockk<MailSession>(relaxed = true)
    private val tester = MailAccountConnectionTester(connector)

    @Test
    fun `a successful login closes the session and reports success`() = runTest {
        val server = slot<MailServer>()
        val credentials = slot<MailCredentials>()
        coEvery { connector.connect(capture(server), capture(credentials)) } returns
            MailResult.Success(session)

        val result = tester.test(accountInput(username = " ana "))

        assertEquals(ConnectionTestResult.Success, result)
        assertEquals(MailServer("imap.example.test", 993, TransportSecurity.TLS), server.captured)
        assertEquals(MailCredentials.Password("ana", "app-password"), credentials.captured)
        coVerify(exactly = 1) { session.close() }
    }

    @Test
    fun `STARTTLS endpoints are passed as STARTTLS`() = runTest {
        val server = slot<MailServer>()
        coEvery { connector.connect(capture(server), any()) } returns MailResult.Success(session)

        tester.test(
            accountInput(
                imap = ServerEndpoint("imap.example.test", 143, ConnectionSecurity.STARTTLS)
            )
        )

        assertEquals(TransportSecurity.STARTTLS, server.captured.security)
    }

    @Test
    fun `each mail failure maps to its connection failure class`() = runTest {
        val expected = mapOf(
            MailResult.AuthenticationFailed to ConnectionFailure.AUTHENTICATION_FAILED,
            MailResult.NetworkUnavailable to ConnectionFailure.HOST_UNREACHABLE,
            MailResult.Timeout to ConnectionFailure.TIMEOUT,
            MailResult.CertificateRejected to ConnectionFailure.TLS_ERROR,
            MailResult.Unsupported("STARTTLS") to ConnectionFailure.TLS_ERROR,
            MailResult.Protocol to ConnectionFailure.UNKNOWN,
            MailResult.NotFound to ConnectionFailure.UNKNOWN,
            MailResult.Unknown to ConnectionFailure.UNKNOWN,
            MailResult.ServerRejected(RejectionKind.NO, permanent = true) to
                ConnectionFailure.UNKNOWN
        )
        expected.forEach { (failure, reason) ->
            coEvery { connector.connect(any(), any()) } returns failure
            assertEquals(
                ConnectionTestResult.Failure(reason),
                tester.test(accountInput()),
                failure.toString()
            )
        }
    }

    @Test
    fun `a password account without a password fails without connecting`() = runTest {
        val result = tester.test(accountInput(credentials = AccountCredentials()))

        assertEquals(ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED), result)
        coVerify(exactly = 0) { connector.connect(any(), any()) }
    }

    @Test
    fun `OAuth accounts log in with the bearer token`() = runTest {
        val credentials = slot<MailCredentials>()
        coEvery { connector.connect(any(), capture(credentials)) } returns
            MailResult.Success(session)

        tester.test(
            accountInput(
                authType = AuthType.OAUTH_GOOGLE,
                credentials = AccountCredentials(oauth = OAuthTokens("token", null, null))
            )
        )

        assertEquals(MailCredentials.OAuthBearer("ana@example.test", "token"), credentials.captured)
    }

    @Test
    fun `an OAuth account without tokens fails authentication`() = runTest {
        val result = tester.test(
            accountInput(authType = AuthType.OAUTH_MICROSOFT, credentials = AccountCredentials())
        )

        assertEquals(ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED), result)
    }
}
