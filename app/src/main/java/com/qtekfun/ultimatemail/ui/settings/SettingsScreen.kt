// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.qtekfun.ultimatemail.BuildConfig
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.settings.AppLanguage

/** The settings of the whole app, in sections; the accounts lead to their own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            AppearanceSection(state, actions)
            LanguageSection()
            GesturesSection(state, actions)
            PrivacySection(state, actions)
            AccountsSection(state, actions)
            AboutSection()
        }
    }
}

@Composable
private fun AppearanceSection(state: SettingsState, actions: SettingsActions) {
    val settings = state.settings
    SectionHeader(stringResource(R.string.settings_section_appearance))
    ChoiceRow(
        title = stringResource(R.string.settings_theme),
        options = ThemeMode.entries,
        selected = settings.theme,
        label = { stringResource(it.label()) },
        onSelect = actions.onThemeChange
    )
    ChoiceRow(
        title = stringResource(R.string.settings_density),
        options = DisplayDensity.entries,
        selected = settings.density,
        label = { stringResource(it.label()) },
        onSelect = actions.onDensityChange
    )
    SettingsSummary(stringResource(R.string.settings_density_summary))
    SwitchRow(
        title = stringResource(R.string.settings_dynamic_color),
        summary = stringResource(R.string.settings_dynamic_color_summary),
        checked = settings.dynamicColor,
        onCheckedChange = actions.onDynamicColorChange
    )
    SwitchRow(
        title = stringResource(R.string.settings_amoled),
        summary = stringResource(R.string.settings_amoled_summary),
        checked = settings.amoled,
        onCheckedChange = actions.onAmoledChange
    )
}

@Composable
private fun LanguageSection() {
    SectionHeader(stringResource(R.string.settings_section_language))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        LanguageChoice()
    } else {
        SettingsSummary(stringResource(R.string.settings_language_unsupported))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageChoice() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguages.current(context)) }
    ChoiceRow(
        title = stringResource(R.string.settings_language),
        options = AppLanguage.entries,
        selected = language,
        label = { stringResource(it.label()) },
        onSelect = {
            language = it
            AppLanguages.set(context, it)
        }
    )
}

@Composable
private fun GesturesSection(state: SettingsState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_section_gestures))
    ChoiceRow(
        title = stringResource(R.string.settings_swipe_right),
        options = SwipeAction.entries,
        selected = state.settings.swipe.right,
        label = { stringResource(it.label()) },
        onSelect = actions.onSwipeRightChange
    )
    ChoiceRow(
        title = stringResource(R.string.settings_swipe_left),
        options = SwipeAction.entries,
        selected = state.settings.swipe.left,
        label = { stringResource(it.label()) },
        onSelect = actions.onSwipeLeftChange
    )
}

@Composable
private fun PrivacySection(state: SettingsState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_section_privacy))
    ChoiceRow(
        title = stringResource(R.string.settings_remote_content),
        options = RemoteContentPolicy.entries,
        selected = state.settings.remoteContent,
        label = { stringResource(it.label()) },
        onSelect = actions.onRemoteContentChange
    )
    SettingsSummary(stringResource(R.string.settings_remote_content_summary))
}

@Composable
private fun AccountsSection(state: SettingsState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_section_accounts))
    if (state.accounts.isEmpty()) {
        SettingsSummary(stringResource(R.string.settings_no_accounts))
    }
    state.accounts.forEach { account ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(role = Role.Button) { actions.onOpenAccount(account.id) }
                .padding(horizontal = ScreenPadding, vertical = 8.dp)
        ) {
            Text(
                account.displayName.ifBlank {
                    account.email
                },
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                account.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val sourceUrl = stringResource(R.string.settings_source_url)
    val openFailed = stringResource(R.string.settings_open_failed)
    SectionHeader(stringResource(R.string.settings_section_about))
    InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
    InfoRow(
        stringResource(R.string.settings_license),
        stringResource(R.string.settings_license_value)
    )
    InfoRow(
        title = stringResource(R.string.settings_source),
        value = sourceUrl,
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, sourceUrl.toUri()))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
            }
        }
    )
}

@Composable
private fun InfoRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .then(
                if (onClick !=
                    null
                ) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = ScreenPadding, vertical = 8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
