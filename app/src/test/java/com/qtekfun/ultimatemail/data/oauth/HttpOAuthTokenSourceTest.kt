// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviderConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The token endpoint is a JDK HTTP server on localhost: no mocks around the HTTP layer. */
class HttpOAuthTokenSourceTest {
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var body = ""
    private var requestBody = ""
    private var contentType: String? = null
    private var requests = 0

    init {
        server.createContext("/token") { exchange ->
            requests++
            requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun config(endpoint: String = "http://127.0.0.1:${server.address.port}/token") =
        OAuthProviderConfig(
            clientId = "client-id",
            scopes = listOf("openid", "offline_access", "https://outlook.office.com/SMTP.Send"),
            authorizationEndpoint = "https://example.test/authorize",
            tokenEndpoint = endpoint,
            redirectUri = "com.example.mail:/oauth2redirect"
        )

    private fun source(config: OAuthProviderConfig? = config()) =
        HttpOAuthTokenSource({ config }, Dispatchers.IO, clock)

    private fun refreshed(result: OAuthRefreshResult): OAuthTokens =
        (result as OAuthRefreshResult.Refreshed).tokens

    @Test
    fun `a refresh posts the grant as a form and returns the new tokens`() = runTest {
        body = """{"token_type":"Bearer","access_token":"new-access","expires_in":3599,""" +
            """"refresh_token":"same-refresh","scope":"openid"}"""

        val tokens = refreshed(source().refresh(AuthType.OAUTH_MICROSOFT, "old-refresh"))

        assertEquals("new-access", tokens.accessToken)
        assertEquals("same-refresh", tokens.refreshToken)
        assertEquals(now.plusSeconds(3599), tokens.expiresAt)
        assertEquals("application/x-www-form-urlencoded", contentType)
        val form = requestBody.split('&').map { it.split('=', limit = 2) }
            .associate { (key, value) -> key to value }
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old-refresh", form["refresh_token"])
        assertEquals("client-id", form["client_id"])
        assertEquals(
            "openid+offline_access+https%3A%2F%2Foutlook.office.com%2FSMTP.Send",
            form["scope"]
        )
        // A public client never sends a secret.
        assertFalse(requestBody.contains("secret"))
    }

    @Test
    fun `a rotated refresh token is returned so the caller can store it`() = runTest {
        body = """{"access_token":"a2","expires_in":"3600","refresh_token":"rotated"}"""

        val tokens = refreshed(source().refresh(AuthType.OAUTH_MICROSOFT, "old-refresh"))

        assertEquals("rotated", tokens.refreshToken)
        assertEquals(now.plusSeconds(3600), tokens.expiresAt)
    }

    @Test
    fun `an answer without a refresh token or expiry leaves them out`() = runTest {
        body = """{"access_token":"a3"}"""

        val tokens = refreshed(source().refresh(AuthType.OAUTH_GOOGLE, "r"))

        assertEquals("a3", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertNull(tokens.expiresAt)
    }

    @Test
    fun `invalid_grant means the user must sign in again`() = runTest {
        status = 400
        body = """{"error":"invalid_grant","error_description":"AADSTS70008: expired"}"""

        assertEquals(
            OAuthRefreshResult.Revoked,
            source().refresh(AuthType.OAUTH_MICROSOFT, "r")
        )
    }

    @Test
    fun `other errors that cannot be fixed by waiting also need a new sign-in`() = runTest {
        status = 401
        for (error in listOf("invalid_client", "unauthorized_client", "interaction_required")) {
            body = """{"error":"$error"}"""

            assertEquals(OAuthRefreshResult.Revoked, source().refresh(AuthType.OAUTH_GOOGLE, "r"))
        }
    }

    @Test
    fun `a server error is retried later`() = runTest {
        status = 503
        body = """{"error":"temporarily_unavailable"}"""

        assertEquals(
            OAuthRefreshResult.TemporaryFailure,
            source().refresh(AuthType.OAUTH_MICROSOFT, "r")
        )
    }

    @Test
    fun `an error that is not about the grant is retried later, not revoked`() = runTest {
        status = 400
        body = """{"error":"invalid_request"}"""

        assertEquals(
            OAuthRefreshResult.TemporaryFailure,
            source().refresh(AuthType.OAUTH_MICROSOFT, "r")
        )
    }

    @Test
    fun `an invalid_grant that comes with a server status is not trusted`() = runTest {
        status = 500
        body = """{"error":"invalid_grant"}"""

        assertEquals(
            OAuthRefreshResult.TemporaryFailure,
            source().refresh(AuthType.OAUTH_MICROSOFT, "r")
        )
    }

    @Test
    fun `a success answer without an access token is retried later`() = runTest {
        body = """{"token_type":"Bearer"}"""

        assertEquals(
            OAuthRefreshResult.TemporaryFailure,
            source().refresh(AuthType.OAUTH_MICROSOFT, "r")
        )
    }

    @Test
    fun `a network failure is retried later`() = runTest {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = source(config("http://127.0.0.1:$closedPort/token"))
            .refresh(AuthType.OAUTH_MICROSOFT, "r")

        assertEquals(OAuthRefreshResult.TemporaryFailure, result)
    }

    @Test
    fun `without a client ID nothing is requested`() = runTest {
        val result = source(null).refresh(AuthType.OAUTH_MICROSOFT, "r")

        assertEquals(OAuthRefreshResult.Unavailable, result)
        assertEquals(0, requests)
    }
}
