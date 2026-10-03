// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.compose.DraftListItem
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import kotlinx.coroutines.launch

/** The view models of the compose feature, created by the activity and handed to `AppRoot`. */
class ComposeScreens(
    val entry: ComposeEntryViewModel,
    val composer: ComposerViewModel,
    val drafts: DraftsViewModel,
    val outbox: OutboxViewModel
)

/**
 * What has to happen whatever the screen: a new draft (a reply, a share, an Undo) opens the
 * composer, and a message from another app that needs an account asks which one.
 */
@Composable
fun ComposeEntryEffects(screens: ComposeScreens, navigator: AppNavigator) {
    LaunchedEffect(screens, navigator) {
        screens.entry.opened.collect { navigator.openCompose(it) }
    }
    val choice by screens.entry.choosing.collectAsStateWithLifecycle()
    choice?.let { waiting ->
        AlertDialog(
            onDismissRequest = screens.entry::dismissChoice,
            title = { Text(stringResource(R.string.incoming_choose_title)) },
            text = {
                Column {
                    waiting.accounts.forEach { account ->
                        Text(
                            account.email,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { screens.entry.chooseAccount(account.id) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = screens.entry::dismissChoice,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** The Outbox screen, connected to its view model. */
@Composable
fun OutboxRoute(viewModel: OutboxViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OutboxScreen(
        state = state,
        actions = OutboxScreenActions(
            onBack = { navigator.back() },
            onRetry = viewModel::retry,
            onEdit = viewModel::edit,
            onRequestDiscard = viewModel::requestDiscard,
            onConfirmDiscard = viewModel::confirmDiscard,
            onDismissPrompt = viewModel::dismissPrompt
        )
    )
}

/** The Drafts folder of [accountId], shown in the main screen instead of the message list. */
@Composable
fun DraftsRoute(accountId: Long, viewModel: DraftsViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coroutines = rememberCoroutineScope()
    LaunchedEffect(accountId) { viewModel.show(accountId) }
    DraftsScreen(
        title = stringResource(R.string.folder_drafts),
        state = state,
        actions = DraftsActions(
            onOpenMenu = { navigator.setDrawerOpen(true) },
            onOpen = viewModel::open,
            onOpenServerDraft = { draft ->
                coroutines.launch {
                    viewModel.serverDraft(DraftListItem.OnServer(draft))?.let {
                        navigator.openConversation(it.accountId, it.folderPath, it.threadId)
                    }
                }
            },
            onRequestDelete = viewModel::requestDelete,
            onDismissDelete = viewModel::dismissDelete,
            onConfirmDelete = viewModel::confirmDelete
        )
    )
}
