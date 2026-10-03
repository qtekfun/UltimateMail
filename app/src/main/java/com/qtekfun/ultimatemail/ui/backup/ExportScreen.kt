// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.backup.BackupFormat
import com.qtekfun.ultimatemail.domain.backup.BackupPassphrase
import com.qtekfun.ultimatemail.domain.backup.PassphraseIssue
import com.qtekfun.ultimatemail.ui.settings.SwitchRow

/**
 * Export accounts (RF-12): passphrase, confirmation and the credentials switch, then the system
 * file creator. What is typed lives only in this screen's memory (not in saved state) and goes
 * to the view model as arrays that are wiped after use.
 */
@Composable
fun ExportScreen(state: ExportState, actions: ExportActions, modifier: Modifier = Modifier) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)
    ) { uri -> actions.onLocationChosen(uri?.toString()) }
    LaunchedEffect(state.stage) {
        when (state.stage) {
            ExportStage.CHOOSING_LOCATION -> launcher.launch(BackupFormat.SUGGESTED_FILE_NAME)

            ExportStage.DONE, ExportStage.FAILED -> {
                passphrase = ""
                confirmation = ""
            }

            else -> Unit
        }
    }
    BackupScaffold(
        title = stringResource(R.string.backup_export_title),
        onBack = actions.onBack,
        modifier = modifier
    ) {
        when (state.stage) {
            ExportStage.FORM, ExportStage.CHOOSING_LOCATION -> ExportForm(
                state = state,
                actions = actions,
                passphrase = passphrase,
                confirmation = confirmation,
                onPassphraseChange = {
                    passphrase = it
                    actions.onPassphraseChange(it.toCharArray())
                },
                onConfirmationChange = { confirmation = it }
            )

            ExportStage.WRITING -> BackupProgress(stringResource(R.string.backup_writing))

            ExportStage.DONE -> ExportDone(state, actions)

            ExportStage.FAILED -> {
                BackupText(stringResource(R.string.backup_export_failed))
                BackupButton(stringResource(R.string.backup_try_again), actions.onReset)
            }
        }
    }
}

@Suppress("LongParameterList") // The two typed texts and their handlers belong together.
@Composable
private fun ExportForm(
    state: ExportState,
    actions: ExportActions,
    passphrase: String,
    confirmation: String,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit
) {
    BackupText(stringResource(R.string.backup_export_intro))
    PassphraseField(
        label = stringResource(R.string.backup_passphrase),
        value = passphrase,
        onValueChange = onPassphraseChange,
        error = state.issue?.takeIf { it != PassphraseIssue.MISMATCH }?.let { issueText(it) }
    )
    state.strength.label()?.let { label ->
        Text(
            text = stringResource(R.string.backup_strength, stringResource(label)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    PassphraseField(
        label = stringResource(R.string.backup_passphrase_confirm),
        value = confirmation,
        onValueChange = onConfirmationChange,
        error = state.issue?.takeIf { it == PassphraseIssue.MISMATCH }?.let { issueText(it) }
    )
    SwitchRow(
        title = stringResource(R.string.backup_include_credentials),
        summary = stringResource(R.string.backup_include_credentials_warning),
        checked = state.includeCredentials,
        onCheckedChange = actions.onIncludeCredentialsChange
    )
    BackupButton(
        text = stringResource(R.string.backup_export_button),
        onClick = { actions.onSubmit(passphrase.toCharArray(), confirmation.toCharArray()) },
        enabled = state.stage == ExportStage.FORM
    )
}

@Composable
private fun issueText(issue: PassphraseIssue): String = when (issue) {
    PassphraseIssue.EMPTY -> stringResource(R.string.backup_issue_empty)

    PassphraseIssue.TOO_SHORT ->
        stringResource(R.string.backup_issue_short, BackupPassphrase.MIN_LENGTH_WITH_CREDENTIALS)

    PassphraseIssue.MISMATCH -> stringResource(R.string.backup_issue_mismatch)
}

@Composable
private fun ExportDone(state: ExportState, actions: ExportActions) {
    BackupText(stringResource(R.string.backup_export_done, state.exportedAccounts))
    BackupText(
        stringResource(
            if (state.credentialsIncluded) {
                R.string.backup_export_done_credentials
            } else {
                R.string.backup_export_done_no_credentials
            }
        )
    )
    BackupButton(stringResource(R.string.backup_done), actions.onBack)
}
