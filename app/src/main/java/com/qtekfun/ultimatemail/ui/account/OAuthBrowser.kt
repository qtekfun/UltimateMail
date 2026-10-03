// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviderConfig
import java.time.Instant
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse

/**
 * Opens the browser for the pending [request] (authorization code + PKCE through AppAuth, the
 * same flow as the debug screen), exchanges the code for tokens and reports the outcome. It holds
 * no rules: what to do with the tokens is up to the view model.
 */
@Composable
fun OAuthBrowserEffect(
    request: OAuthRequest?,
    onLaunched: () -> Unit,
    onResult: (OAuthBrowserResult) -> Unit
) {
    val context = LocalContext.current
    val service = remember { AuthorizationService(context) }
    DisposableEffect(service) { onDispose { service.dispose() } }
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val data = activityResult.data
        val response = data?.let(AuthorizationResponse::fromIntent)
        val failure = data?.let(AuthorizationException::fromIntent)
        if (response == null || failure != null) {
            currentOnResult(OAuthBrowserResult.Cancelled)
        } else {
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, _ ->
                currentOnResult(tokens?.toResult() ?: OAuthBrowserResult.Failed)
            }
        }
    }
    LaunchedEffect(request) {
        if (request != null) {
            launcher.launch(service.getAuthorizationRequestIntent(request.config.toAppAuth()))
            onLaunched()
        }
    }
}

private fun OAuthProviderConfig.toAppAuth() = AuthorizationRequest.Builder(
    AuthorizationServiceConfiguration(authorizationEndpoint.toUri(), tokenEndpoint.toUri()),
    clientId,
    ResponseTypeValues.CODE,
    redirectUri.toUri()
).setScopes(scopes).build()

private fun TokenResponse.toResult(): OAuthBrowserResult {
    val access = accessToken ?: return OAuthBrowserResult.Failed
    val tokens = OAuthTokens(
        accessToken = access,
        refreshToken = refreshToken,
        expiresAt = accessTokenExpirationTime?.let(Instant::ofEpochMilli)
    )
    return OAuthBrowserResult.Success(tokens, idToken)
}
