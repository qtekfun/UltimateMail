// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.qtekfun.ultimatemail.BuildConfig
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.oauth.ClientIdPreferences
import com.qtekfun.ultimatemail.domain.oauth.GoogleClientId
import com.qtekfun.ultimatemail.domain.oauth.IdTokenEmail
import com.qtekfun.ultimatemail.domain.oauth.OAuthLookup
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviderConfig
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviders
import com.qtekfun.ultimatemail.ui.theme.UltimateMailTheme
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.eclipse.angus.mail.imap.IMAPStore

/**
 * Debug-only check of the whole Google sign-in (T02): the browser flow with PKCE through AppAuth,
 * then a real IMAP login with XOAUTH2. Nothing is stored and no token or address is logged.
 */
class OAuthDebugActivity : ComponentActivity() {
    private lateinit var authService: AuthorizationService
    private lateinit var clientIds: ClientIdPreferences
    private var clientIdText by mutableStateOf("")
    private var status by mutableStateOf<StatusText>(StatusText.Res(R.string.oauth_debug_idle))

    private val authorization =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val response = data?.let(AuthorizationResponse::fromIntent)
            val failure = data?.let(net.openid.appauth.AuthorizationException::fromIntent)
            if (response == null) {
                status =
                    StatusText.Res(R.string.oauth_debug_cancelled, failure?.errorDescription ?: "-")
            } else {
                authService.performTokenRequest(response.createTokenExchangeRequest()) {
                        tokens,
                        error
                    ->
                    if (tokens?.accessToken == null) {
                        status = StatusText.Res(
                            R.string.oauth_debug_token_failed,
                            error?.errorDescription ?: "-"
                        )
                    } else {
                        checkImap(tokens.accessToken.orEmpty(), IdTokenEmail.from(tokens.idToken))
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authService = AuthorizationService(this)
        clientIds = ClientIdPreferences(this)
        clientIdText = clientIds.google() ?: BuildConfig.GOOGLE_CLIENT_ID
        setContent {
            UltimateMailTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.oauth_debug_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        OutlinedTextField(
                            value = clientIdText,
                            onValueChange = { clientIdText = it },
                            label = { Text(stringResource(R.string.oauth_debug_client_id)) },
                            isError = !GoogleClientId.isValid(clientIdText),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = ::signIn) {
                            Text(stringResource(R.string.oauth_debug_button))
                        }
                        Text(status.resolve())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        authService.dispose()
        super.onDestroy()
    }

    private fun signIn() {
        if (!GoogleClientId.isValid(clientIdText)) {
            status = StatusText.Res(R.string.oauth_debug_client_id_invalid)
            return
        }
        clientIds.setGoogle(clientIdText)
        val providers =
            OAuthProviders(GoogleClientId.normalize(clientIdText), BuildConfig.APPLICATION_ID)
        when (val lookup = providers.forHost(GMAIL_IMAP_HOST)) {
            is OAuthLookup.Available -> {
                status = StatusText.Res(R.string.oauth_debug_authorizing)
                authorization.launch(
                    authService.getAuthorizationRequestIntent(request(lookup.config))
                )
            }

            else -> status = StatusText.Res(R.string.oauth_debug_not_configured)
        }
    }

    private fun request(config: OAuthProviderConfig) = AuthorizationRequest.Builder(
        AuthorizationServiceConfiguration(
            config.authorizationEndpoint.toUri(),
            config.tokenEndpoint.toUri()
        ),
        config.clientId,
        ResponseTypeValues.CODE,
        config.redirectUri.toUri()
    ).setScopes(config.scopes).build()

    private fun checkImap(accessToken: String, email: String?) {
        if (email == null) {
            status = StatusText.Res(R.string.oauth_debug_no_email)
            return
        }
        status = StatusText.Res(R.string.oauth_debug_connecting)
        lifecycleScope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { countFolders(email, accessToken) }.fold(
                    onSuccess = { StatusText.Res(R.string.oauth_debug_success, it) },
                    onFailure = {
                        StatusText.Res(R.string.oauth_debug_imap_failed, it.javaClass.simpleName)
                    }
                )
            }
        }
    }

    private fun countFolders(email: String, accessToken: String): Int {
        val properties = Properties().apply {
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.sasl.enable", "true")
            put("mail.imap.sasl.mechanisms", "XOAUTH2")
            put("mail.imap.auth.login.disable", "true")
            put("mail.imap.auth.plain.disable", "true")
            put("mail.imap.connectiontimeout", "10000")
            put("mail.imap.timeout", "10000")
        }
        val store = jakarta.mail.Session.getInstance(properties).getStore("imap") as IMAPStore
        return store.use {
            it.connect(GMAIL_IMAP_HOST, email, accessToken)
            it.defaultFolder.list("*").size
        }
    }

    private sealed interface StatusText {
        class Res(val id: Int, val arg: Any? = null) : StatusText
    }

    @androidx.compose.runtime.Composable
    private fun StatusText.resolve(): String = when (this) {
        is StatusText.Res -> if (arg == null) stringResource(id) else stringResource(id, arg)
    }

    private companion object {
        const val GMAIL_IMAP_HOST = "imap.gmail.com"
    }
}
