// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.oauth.OAuthFeature

/** Sign in with the provider, for servers that support it: the user brings their own client ID. */
@Composable
internal fun OAuthSection(state: AddAccountState, actions: AddAccountActions) {
    if (!OAuthFeature.ENABLED) return
    val authType = state.oauthType ?: return
    val microsoft = authType == AuthType.OAUTH_MICROSOFT
    Text(
        text = stringResource(R.string.oauth_or_divider),
        style = MaterialTheme.typography.titleSmall
    )
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
        value = state.clientIdText,
        label = if (microsoft) {
            R.string.field_client_id_microsoft
        } else {
            R.string.field_client_id_google
        },
        error = state.errorFor(FormField.CLIENT_ID),
        enabled = !state.busy,
        keyboardType = KeyboardType.Ascii,
        onChange = { actions.onTextChange(FormInput.CLIENT_ID, it) }
    )
    OutlinedButton(
        onClick = actions.onSignIn,
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .minimumTouchTarget()
    ) {
        val label = if (microsoft) R.string.sign_in_microsoft else R.string.sign_in_google
        Text(stringResource(label))
    }
}
