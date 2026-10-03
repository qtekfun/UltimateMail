// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.backup.BackupPreview
import com.qtekfun.ultimatemail.domain.backup.ImportSummary
import com.qtekfun.ultimatemail.domain.backup.PreviewEntry
import com.qtekfun.ultimatemail.domain.backup.PreviewStatus
import com.qtekfun.ultimatemail.ui.settings.MinTouchTarget
import com.qtekfun.ultimatemail.ui.settings.SwitchRow

/** What the import screen can do. */
data class ImportActions(
    val onBack: () -> Unit,
    val onFileChosen: (String?) -> Unit,
    val onOpen: (CharArray) -> Unit,
    val onToggle: (Int) -> Unit,
    val onImportSettingsChange: (Boolean) -> Unit,
    val onImport: () -> Unit
)

/** Import accounts (RF-12): pick the file, passphrase, preview with checkboxes, summary. */
@Composable
fun ImportScreen(state: ImportState, actions: ImportActions, modifier: Modifier = Modifier) {
    BackupScaffold(
        title = stringResource(R.string.backup_import_title),
        onBack = actions.onBack,
        modifier = modifier
    ) {
        when (state.stage) {
            ImportStage.PICK -> PickStep(actions)
            ImportStage.PASSPHRASE -> PassphraseStep(state, actions)
            ImportStage.OPENING -> BackupProgress(stringResource(R.string.backup_opening))
            ImportStage.PREVIEW -> state.preview?.let { PreviewStep(it, state, actions) }
            ImportStage.IMPORTING -> BackupProgress(stringResource(R.string.backup_importing))
            ImportStage.DONE -> state.summary?.let { SummaryStep(it, actions) }
        }
    }
}

@Composable
private fun PickStep(actions: ImportActions) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> actions.onFileChosen(uri?.toString()) }
    BackupText(stringResource(R.string.backup_import_intro))
    // Any type: the file has no registered MIME type, and a filter would grey it out.
    BackupButton(stringResource(R.string.backup_pick_file), { launcher.launch(arrayOf("*/*")) })
}

@Composable
private fun PassphraseStep(state: ImportState, actions: ImportActions) {
    var passphrase by remember { mutableStateOf("") }
    // The text is not kept after a try: a wrong passphrase is retyped.
    LaunchedEffect(state.error) { passphrase = "" }
    BackupText(stringResource(R.string.backup_import_passphrase_intro))
    PassphraseField(
        label = stringResource(R.string.backup_passphrase),
        value = passphrase,
        onValueChange = { passphrase = it },
        error = state.error?.let { stringResource(it.message()) }
    )
    BackupButton(
        text = stringResource(R.string.backup_open_button),
        onClick = { actions.onOpen(passphrase.toCharArray()) },
        enabled = passphrase.isNotEmpty()
    )
}

@Composable
private fun PreviewStep(preview: BackupPreview, state: ImportState, actions: ImportActions) {
    BackupText(stringResource(R.string.backup_preview_intro))
    preview.entries.forEach { entry ->
        EntryRow(entry, entry.index in state.selected) { actions.onToggle(entry.index) }
    }
    SwitchRow(
        title = stringResource(R.string.backup_import_settings),
        summary = stringResource(
            if (preview.hasSettings) {
                R.string.backup_import_settings_summary
            } else {
                R.string.backup_import_settings_none
            }
        ),
        checked = state.importSettings && preview.hasSettings,
        onCheckedChange = actions.onImportSettingsChange,
        enabled = preview.hasSettings
    )
    BackupButton(
        text = stringResource(R.string.backup_import_button),
        onClick = actions.onImport,
        enabled = state.selected.isNotEmpty() || (state.importSettings && preview.hasSettings)
    )
}

@Composable
private fun EntryRow(entry: PreviewEntry, checked: Boolean, onToggle: () -> Unit) {
    val importable = entry.status == PreviewStatus.IMPORTABLE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = checked,
                enabled = importable,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The row handles the click, so the checkbox itself is not a second touch target.
        Checkbox(checked = checked, onCheckedChange = null, enabled = importable)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.email, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(entry.authType.providerLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val note = entry.status.explanation()?.let { stringResource(it) }
                ?: stringResource(
                    if (entry.hasCredentials) {
                        R.string.backup_entry_credentials_included
                    } else {
                        R.string.backup_entry_sign_in_needed
                    }
                )
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (importable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun SummaryStep(summary: ImportSummary, actions: ImportActions) {
    BackupText(stringResource(R.string.backup_result_imported, summary.imported.size))
    if (summary.skipped > 0) {
        BackupText(stringResource(R.string.backup_result_skipped, summary.skipped))
    }
    if (summary.failed > 0) {
        BackupText(stringResource(R.string.backup_result_failed, summary.failed))
    }
    if (summary.settingsApplied) BackupText(stringResource(R.string.backup_result_settings))
    val needSignIn = summary.imported.filter { it.needsSignIn }
    if (needSignIn.isNotEmpty()) {
        BackupText(
            stringResource(
                R.string.backup_result_sign_in,
                needSignIn.joinToString(", ") { it.email }
            )
        )
    }
    if (summary.imported.isNotEmpty()) BackupText(stringResource(R.string.backup_result_sync))
    BackupButton(stringResource(R.string.backup_done), actions.onBack)
}
