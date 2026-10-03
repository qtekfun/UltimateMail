// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.ReauthTarget
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult

private val FormPadding = 16.dp

/** What the re-authentication screen can do. */
data class ReauthActions(
    val onBack: () -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onClientIdChange: (String) -> Unit,
    val onSubmitPassword: () -> Unit,
    val onSignIn: () -> Unit,
    val onCancel: () -> Unit,
    val onOAuthLaunched: () -> Unit,
    val onOAuthResult: (OAuthBrowserResult) -> Unit
)

/** Sign in again to an existing account: a password, or the provider's browser sign-in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReauthScreen(state: ReauthState, actions: ReauthActions, modifier: Modifier = Modifier) {
    OAuthBrowserEffect(state.oauthRequest, actions.onOAuthLaunched, actions.onOAuthResult)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reauth_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.minimumTouchTarget()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(FormPadding),
            verticalArrangement = Arrangement.spacedBy(FormPadding)
        ) {
            state.target?.let { ReauthForm(it, state, actions) }
            state.failure?.let { ReauthError(stringResource(it.toMessage())) }
            ReauthProgressArea(state, actions)
        }
    }
}

@Composable
private fun ReauthForm(target: ReauthTarget, state: ReauthState, actions: ReauthActions) {
    Text(
        text = stringResource(R.string.reauth_account_line, target.email, target.imapHost),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = stringResource(
            if (state.oauthType ==
                null
            ) {
                R.string.reauth_intro_password
            } else {
                R.string.reauth_intro_oauth
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.oauthType == null) {
        PasswordForm(state, actions)
    } else {
        OAuthForm(state, actions)
    }
}

@Composable
private fun PasswordForm(state: ReauthState, actions: ReauthActions) {
    if (state.gmailAppPasswordHint) {
        Text(
            text = stringResource(R.string.reauth_hint_gmail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    FormTextField(
        value = state.password,
        label = R.string.field_password,
        error = null,
        enabled = !state.busy,
        keyboardType = KeyboardType.Password,
        isPassword = true,
        onChange = actions.onPasswordChange
    )
    if (!state.busy) {
        Button(
            onClick = actions.onSubmitPassword,
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
        ) {
            Text(stringResource(R.string.reauth_submit))
        }
    }
}

@Composable
private fun OAuthForm(state: ReauthState, actions: ReauthActions) {
    val microsoft = state.oauthType == AuthType.OAUTH_MICROSOFT
    Text(
        text = stringResource(
            if (microsoft) {
                R.string.oauth_client_id_help_microsoft
            } else {
                R.string.oauth_client_id_help_google
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    FormTextField(
        value = state.clientId,
        label = if (microsoft) {
            R.string.field_client_id_microsoft
        } else {
            R.string.field_client_id_google
        },
        error = state.clientIdError?.let { FieldError(FormField.CLIENT_ID, it) },
        enabled = !state.busy,
        keyboardType = KeyboardType.Ascii,
        onChange = actions.onClientIdChange
    )
    if (!state.busy) {
        Button(
            onClick = actions.onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
        ) {
            Text(
                stringResource(
                    if (microsoft) R.string.sign_in_microsoft else R.string.sign_in_google
                )
            )
        }
    }
}

@Composable
private fun ReauthProgressArea(state: ReauthState, actions: ReauthActions) {
    if (!state.busy) return
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(
                if (state.progress == ReauthProgress.SIGNING_IN) {
                    R.string.add_account_signing_in
                } else {
                    R.string.add_account_testing
                }
            )
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    OutlinedButton(
        onClick = actions.onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .minimumTouchTarget()
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun ReauthError(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
    )
}
