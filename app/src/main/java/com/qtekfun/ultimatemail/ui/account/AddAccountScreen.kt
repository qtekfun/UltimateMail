// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.oauth.OAuthBrowserResult

private val FormPadding = 16.dp
private val MinTouchTarget = 48.dp

/** The add-account form: address and password, with the server settings under "advanced". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    state: AddAccountState,
    actions: AddAccountActions,
    modifier: Modifier = Modifier
) {
    OAuthBrowserEffect(state.oauthRequest, actions.onOAuthLaunched, actions.onOAuthResult)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_account_title)) },
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
            CredentialsFields(state, actions)
            ProviderHintText(state.hint)
            OAuthSection(state, actions)
            AdvancedSection(state, actions)
            state.errorFor(FormField.GENERAL)?.let { ErrorText(stringResource(it.message)) }
            state.failure?.let { ErrorText(stringResource(it.toMessage())) }
            SubmitArea(state, actions)
        }
    }
}

/** What the screen can do; one object so the composables stay easy to preview and test. */
data class AddAccountActions(
    val onBack: () -> Unit,
    val onTextChange: (FormInput, String) -> Unit,
    val onSecurityChange: (AccountInputError.Server, ConnectionSecurity) -> Unit,
    val onAdvancedToggle: () -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit,
    val onSignIn: () -> Unit,
    val onOAuthLaunched: () -> Unit,
    val onOAuthResult: (OAuthBrowserResult) -> Unit
)

@Composable
private fun CredentialsFields(state: AddAccountState, actions: AddAccountActions) {
    FormTextField(
        value = state.email,
        label = R.string.field_email,
        error = state.errorFor(FormField.EMAIL),
        enabled = !state.busy,
        keyboardType = KeyboardType.Email,
        onChange = { actions.onTextChange(FormInput.EMAIL, it) }
    )
    FormTextField(
        value = state.password,
        label = R.string.field_password,
        error = state.errorFor(FormField.PASSWORD),
        enabled = !state.busy,
        keyboardType = KeyboardType.Password,
        isPassword = true,
        onChange = { actions.onTextChange(FormInput.PASSWORD, it) }
    )
    FormTextField(
        value = state.displayName,
        label = R.string.field_display_name,
        error = null,
        enabled = !state.busy,
        onChange = { actions.onTextChange(FormInput.DISPLAY_NAME, it) }
    )
}

@Composable
private fun ProviderHintText(hint: ProviderHint) {
    val text = when (hint) {
        ProviderHint.NONE -> return
        ProviderHint.GMAIL -> R.string.hint_gmail
        ProviderHint.MICROSOFT -> R.string.hint_microsoft
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AdvancedSection(state: AddAccountState, actions: AddAccountActions) {
    TextButton(
        onClick = actions.onAdvancedToggle,
        modifier = Modifier.minimumTouchTarget()
    ) {
        val label = if (state.advancedExpanded) {
            R.string.advanced_settings_hide
        } else {
            R.string.advanced_settings
        }
        Text(stringResource(label))
    }
    if (!state.advancedExpanded) return
    FormTextField(
        value = state.username,
        label = R.string.field_username,
        error = state.errorFor(FormField.USERNAME),
        enabled = !state.busy,
        onChange = { actions.onTextChange(FormInput.USERNAME, it) }
    )
    ServerFields(
        state = state,
        host = ServerFieldSet(
            hostLabel = R.string.field_imap_host,
            securityLabel = R.string.security_imap_label,
            hostValue = state.imapHost,
            hostField = FormInput.IMAP_HOST,
            hostError = state.errorFor(FormField.IMAP_HOST),
            portValue = state.imapPort,
            portField = FormInput.IMAP_PORT,
            portError = state.errorFor(FormField.IMAP_PORT),
            security = state.imapSecurity,
            onSecurityChange = { actions.onSecurityChange(AccountInputError.Server.IMAP, it) }
        ),
        onTextChange = actions.onTextChange
    )
    ServerFields(
        state = state,
        host = ServerFieldSet(
            hostLabel = R.string.field_smtp_host,
            securityLabel = R.string.security_smtp_label,
            hostValue = state.smtpHost,
            hostField = FormInput.SMTP_HOST,
            hostError = state.errorFor(FormField.SMTP_HOST),
            portValue = state.smtpPort,
            portField = FormInput.SMTP_PORT,
            portError = state.errorFor(FormField.SMTP_PORT),
            security = state.smtpSecurity,
            onSecurityChange = { actions.onSecurityChange(AccountInputError.Server.SMTP, it) }
        ),
        onTextChange = actions.onTextChange
    )
}

private data class ServerFieldSet(
    val hostLabel: Int,
    val securityLabel: Int,
    val hostValue: String,
    val hostField: FormInput,
    val hostError: FieldError?,
    val portValue: String,
    val portField: FormInput,
    val portError: FieldError?,
    val security: ConnectionSecurity,
    val onSecurityChange: (ConnectionSecurity) -> Unit
)

@Composable
private fun ServerFields(
    state: AddAccountState,
    host: ServerFieldSet,
    onTextChange: (FormInput, String) -> Unit
) {
    FormTextField(
        value = host.hostValue,
        label = host.hostLabel,
        error = host.hostError,
        enabled = !state.busy,
        keyboardType = KeyboardType.Uri,
        onChange = { onTextChange(host.hostField, it) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FormPadding)) {
        Column(modifier = Modifier.weight(1f)) {
            FormTextField(
                value = host.portValue,
                label = R.string.field_port,
                error = host.portError,
                enabled = !state.busy,
                keyboardType = KeyboardType.Number,
                onChange = { onTextChange(host.portField, it) }
            )
        }
        Column(modifier = Modifier.weight(2f)) {
            Text(
                stringResource(host.securityLabel),
                style = MaterialTheme.typography.labelMedium
            )
            SecurityChoice(host.security, !state.busy, host.onSecurityChange)
        }
    }
}

@Composable
private fun SecurityChoice(
    selected: ConnectionSecurity,
    enabled: Boolean,
    onChange: (ConnectionSecurity) -> Unit
) {
    ConnectionSecurity.entries.forEach { option ->
        val label = stringResource(
            when (option) {
                ConnectionSecurity.TLS -> R.string.security_tls
                ConnectionSecurity.STARTTLS -> R.string.security_starttls
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(
                    selected = option == selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onChange(option) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The row is the control, so the button itself is not a second focus stop.
            RadioButton(selected = option == selected, onClick = null, enabled = enabled)
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SubmitArea(state: AddAccountState, actions: AddAccountActions) {
    if (state.busy) {
        val progressText = when (state.progress) {
            AddAccountProgress.SIGNING_IN -> R.string.add_account_signing_in
            AddAccountProgress.TESTING -> R.string.add_account_testing
            else -> R.string.add_account_saving
        }
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(progressText))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.progress == AddAccountProgress.TESTING ||
            state.progress == AddAccountProgress.SIGNING_IN
        ) {
            OutlinedButton(
                onClick = actions.onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    } else {
        Button(
            onClick = actions.onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
        ) {
            Text(stringResource(R.string.add_account_submit))
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
    )
}

@Composable
internal fun FormTextField(
    value: String,
    label: Int,
    error: FieldError?,
    enabled: Boolean,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message)) } },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
    )
}

internal fun Modifier.minimumTouchTarget(): Modifier = heightIn(min = MinTouchTarget)
