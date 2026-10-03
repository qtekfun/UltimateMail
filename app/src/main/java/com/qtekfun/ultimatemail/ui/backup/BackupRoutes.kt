// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Connects the export screen to its view model. The view model comes from the activity's Hilt
 * factory (hilt-navigation-compose is not a dependency), and is reset on leaving the screen so
 * nothing typed or read stays in memory.
 */
@Composable
fun ExportRoute(onBack: () -> Unit) {
    val viewModel: ExportViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    ExportScreen(
        state = state,
        actions = ExportActions(
            onBack = onBack,
            onPassphraseChange = viewModel::onPassphraseChange,
            onIncludeCredentialsChange = viewModel::onIncludeCredentialsChange,
            onSubmit = viewModel::submit,
            onLocationChosen = viewModel::onLocationChosen,
            onReset = viewModel::reset
        )
    )
}

/** Connects the import screen to its view model; see [ExportRoute]. */
@Composable
fun ImportRoute(onBack: () -> Unit) {
    val viewModel: ImportViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    ImportScreen(
        state = state,
        actions = ImportActions(
            onBack = onBack,
            onFileChosen = viewModel::onFileChosen,
            onOpen = viewModel::open,
            onToggle = viewModel::toggle,
            onImportSettingsChange = viewModel::onImportSettingsChange,
            onImport = viewModel::import
        )
    )
}
