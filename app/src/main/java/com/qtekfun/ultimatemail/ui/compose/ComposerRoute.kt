// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Connects the composer screen to [ComposerViewModel] and the system file picker
 * (`OpenMultipleDocuments`: no storage permission is needed, the app only gets the files the
 * user picks). [onFinished] goes back when the composer was closed, discarded or sent.
 */
@Composable
fun ComposerRoute(
    draftId: Long,
    viewModel: ComposerViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(draftId) { viewModel.load(draftId) }
    LaunchedEffect(state.phase) {
        if (state.phase == ComposerPhase.FINISHED) {
            onFinished()
            // The next time this draft is opened (Undo) must not find the screen "finished".
            viewModel.acknowledgeFinished()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.attach(it.map { uri -> uri.toString() })
    }
    // Back saves the draft like the close button does.
    BackHandler { viewModel.close() }
    ComposerScreen(
        state = state,
        actions = ComposerActions(
            onClose = viewModel::close,
            onSend = viewModel::send,
            onAttach = { picker.launch(arrayOf(ANY_TYPE)) },
            onRequestDiscard = viewModel::requestDiscard,
            onSelectSender = viewModel::selectSender,
            onInput = viewModel::onInput,
            onCommit = viewModel::commit,
            onPickSuggestion = viewModel::pickSuggestion,
            onRemoveChip = viewModel::removeChip,
            onShowCcBcc = viewModel::showCcBcc,
            onSubject = viewModel::onSubject,
            onBody = viewModel::onBody,
            onRemoveAttachment = viewModel::removeAttachment,
            onDismissMessage = viewModel::dismissMessage,
            onConfirm = viewModel::confirm,
            onDismissDialog = viewModel::dismissDialog
        ),
        modifier = modifier
    )
}

private const val ANY_TYPE = "*/*"
