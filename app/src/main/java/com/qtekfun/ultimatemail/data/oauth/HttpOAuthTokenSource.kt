// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.oauth

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviderConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Refreshes OAuth2 access tokens with the refresh token grant against the token endpoint of the
 * provider (RFC 6749 §6): a public client, so the request carries the client ID and no secret.
 * Persisting the result is the caller's job (the sync engine saves it in the credential vault and
 * keeps the old refresh token when the answer has none). Tokens are never logged.
 *
 * [configFor] gives the provider setup for an account type, or null when it has no client ID.
 */
class HttpOAuthTokenSource(
    private val configFor: (AuthType) -> OAuthProviderConfig?,
    private val io: CoroutineDispatcher,
    private val clock: Clock
) : OAuthTokenSource {
    override suspend fun refresh(authType: AuthType, refreshToken: String): OAuthRefreshResult {
        val config = configFor(authType) ?: return OAuthRefreshResult.Unavailable
        return try {
            interpret(withContext(io) { post(config, refreshToken) })
        } catch (_: IOException) {
            // No network, a timeout or a dropped connection: try again later.
            OAuthRefreshResult.TemporaryFailure
        }
    }

    private fun post(config: OAuthProviderConfig, refreshToken: String): HttpAnswer {
        val form = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to config.clientId,
            "scope" to config.scopes.joinToString(" ")
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val connection = URI(config.tokenEndpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in
                HTTP_OK_RANGE
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpAnswer(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun interpret(answer: HttpAnswer): OAuthRefreshResult = when {
        answer.code in HTTP_OK_RANGE -> tokensOf(answer.body)

        answer.code in HTTP_CLIENT_ERROR_RANGE &&
            stringField(answer.body, "error") in REVOKED_ERRORS -> OAuthRefreshResult.Revoked

        else -> OAuthRefreshResult.TemporaryFailure
    }

    private fun tokensOf(body: String): OAuthRefreshResult =
        stringField(body, "access_token")?.let { access ->
            OAuthRefreshResult.Refreshed(
                OAuthTokens(
                    accessToken = access,
                    refreshToken = stringField(body, "refresh_token"),
                    expiresAt = numberField(body, "expires_in")?.let {
                        clock.instant().plusSeconds(it)
                    }
                )
            )
        } ?: OAuthRefreshResult.TemporaryFailure

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    // The answers are flat JSON objects whose values need no unescaping, so a pattern is enough
    // and avoids a JSON dependency.
    private fun stringField(json: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun numberField(json: String, name: String): Long? =
        Regex("\"$name\"\\s*:\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private data class HttpAnswer(val code: Int, val body: String)

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        val HTTP_OK_RANGE = 200..299
        val HTTP_CLIENT_ERROR_RANGE = 400..499

        /** Answers that mean the refresh token will never work again: sign in once more. */
        val REVOKED_ERRORS = setOf(
            "invalid_grant",
            "invalid_client",
            "unauthorized_client",
            "interaction_required",
            "consent_required",
            "login_required"
        )
    }
}
