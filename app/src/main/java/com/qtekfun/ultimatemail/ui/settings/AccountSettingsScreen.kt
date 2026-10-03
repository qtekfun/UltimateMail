// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.settings.AccountProfile
import com.qtekfun.ultimatemail.domain.settings.FolderSync
import com.qtekfun.ultimatemail.domain.settings.OfflineWindow
import com.qtekfun.ultimatemail.domain.settings.ProfileError
import com.qtekfun.ultimatemail.domain.settings.ProfileRules
import com.qtekfun.ultimatemail.domain.settings.SignaturePreviews
import com.qtekfun.ultimatemail.ui.drawer.RemoveAccountDialog
import com.qtekfun.ultimatemail.ui.drawer.displayName

/** Name and signature, how much mail is kept offline, which folders sync, and removal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    state: AccountSettingsState,
    actions: AccountSettingsActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.heightIn(min = MinTouchTarget)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).imePadding()) {
            item(key = "profile") { ProfileSection(state, actions) }
            item(key = "offline") { OfflineSection(state, actions) }
            item(key = "folders-header") { FoldersHeader(state.folders.isEmpty()) }
            items(state.folders, key = { it.path }) { FolderRow(it, actions) }
            item(key = "remove") {
                OutlinedButton(
                    onClick = actions.onRequestRemoval,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .padding(ScreenPadding)
                ) {
                    Text(stringResource(R.string.account_remove))
                }
            }
        }
    }
    if (state.confirmingRemoval) {
        RemoveAccountDialog(actions.onDismissRemoval, actions.onConfirmRemoval)
    }
}

@Composable
private fun ProfileSection(state: AccountSettingsState, actions: AccountSettingsActions) {
    val profile = state.profile
    Column {
        SettingsSummary(state.email)
        OutlinedTextField(
            value = profile.displayName,
            onValueChange = actions.onNameChange,
            label = { Text(stringResource(R.string.account_settings_name)) },
            singleLine = true,
            isError = ProfileError.NAME_TOO_LONG in state.errors,
            supportingText = if (ProfileError.NAME_TOO_LONG in state.errors) {
                {
                    Text(
                        stringResource(
                            R.string.account_settings_name_too_long,
                            ProfileRules.MAX_NAME_LENGTH
                        )
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp)
        )
        SectionHeader(stringResource(R.string.signature_title))
        SignatureField(state, actions)
        SwitchRow(
            title = stringResource(R.string.signature_use),
            checked = profile.signatureEnabled,
            onCheckedChange = actions.onSignatureEnabledChange
        )
        SwitchRow(
            title = stringResource(R.string.signature_before_quote),
            checked = profile.signatureBeforeQuote,
            onCheckedChange = actions.onBeforeQuoteChange,
            enabled = profile.signatureEnabled
        )
        SignaturePreviewCard(profile)
        SaveRow(state.status, actions.onSave)
    }
}

@Composable
private fun SignatureField(state: AccountSettingsState, actions: AccountSettingsActions) {
    val tooLong = ProfileError.SIGNATURE_TOO_LONG in state.errors
    OutlinedTextField(
        value = state.profile.signature,
        onValueChange = actions.onSignatureChange,
        label = { Text(stringResource(R.string.signature_field)) },
        minLines = 4,
        isError = tooLong,
        supportingText = {
            Text(
                if (tooLong) {
                    stringResource(R.string.signature_too_long, ProfileRules.MAX_SIGNATURE_LENGTH)
                } else {
                    stringResource(
                        R.string.signature_counter,
                        state.profile.signature.length,
                        ProfileRules.MAX_SIGNATURE_LENGTH
                    )
                }
            )
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp)
    )
}

/** The signature over a sample message, built by the same code the composer will use. */
@Composable
private fun SignaturePreviewCard(profile: AccountProfile) {
    val newBody = stringResource(R.string.signature_sample_new)
    val replyBody = stringResource(R.string.signature_sample_reply)
    val preview = remember(profile, newBody, replyBody) {
        SignaturePreviews.build(profile, newBody, replyBody)
    }
    SectionHeader(stringResource(R.string.signature_preview))
    if (preview.newMessage == newBody && preview.reply == replyBody) {
        SettingsSummary(stringResource(R.string.signature_preview_off))
        return
    }
    PreviewBlock(stringResource(R.string.signature_preview_new), preview.newMessage)
    PreviewBlock(stringResource(R.string.signature_preview_reply), preview.reply)
}

@Composable
private fun PreviewBlock(title: String, text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SaveRow(status: ProfileStatus, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(
                when (status) {
                    ProfileStatus.SAVED -> R.string.profile_status_saved
                    ProfileStatus.UNSAVED -> R.string.profile_status_unsaved
                    ProfileStatus.SAVING -> R.string.profile_status_saving
                    ProfileStatus.INVALID -> R.string.profile_status_invalid
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == ProfileStatus.INVALID) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        Button(
            onClick = onSave,
            enabled = status == ProfileStatus.UNSAVED,
            modifier = Modifier.heightIn(min = MinTouchTarget)
        ) {
            Text(stringResource(R.string.profile_save))
        }
    }
}

@Composable
private fun OfflineSection(state: AccountSettingsState, actions: AccountSettingsActions) {
    Column {
        SectionHeader(stringResource(R.string.offline_title))
        SettingsSummary(stringResource(R.string.offline_summary))
        ChoiceRow(
            title = stringResource(R.string.offline_title),
            options = OfflineWindow.entries,
            selected = state.offlineWindow,
            label = { stringResource(it.label()) },
            onSelect = actions.onOfflineWindowChange
        )
        SettingsSummary(stringResource(R.string.offline_note))
    }
}

@Composable
private fun FoldersHeader(empty: Boolean) {
    Column {
        SectionHeader(stringResource(R.string.folders_sync_title))
        SettingsSummary(stringResource(R.string.folders_sync_summary))
        if (empty) SettingsSummary(stringResource(R.string.folders_sync_none))
    }
}

@Composable
private fun FolderRow(folder: FolderSync, actions: AccountSettingsActions) {
    SwitchRow(
        title = folder.role.displayName() ?: folder.name,
        summary = if (folder.canToggle) null else stringResource(R.string.folders_sync_always),
        checked = folder.syncEnabled,
        onCheckedChange = { actions.onFolderSyncChange(folder.path, it) },
        enabled = folder.canToggle
    )
}
