// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.ui.account.AddAccountActions
import com.qtekfun.ultimatemail.ui.account.AddAccountEvent
import com.qtekfun.ultimatemail.ui.account.AddAccountScreen
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.folders.FolderListActions
import com.qtekfun.ultimatemail.ui.folders.FolderListScreen
import com.qtekfun.ultimatemail.ui.folders.FolderListViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxActions
import com.qtekfun.ultimatemail.ui.inbox.InboxScreen
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import com.qtekfun.ultimatemail.ui.nav.Screen

/** Shows the current [Screen] and connects each screen to its view model. */
@Composable
fun AppRoot(
    navigator: AppNavigator,
    folders: FolderListViewModel,
    addAccount: AddAccountViewModel,
    inbox: InboxViewModel
) {
    val screen by navigator.screen.collectAsStateWithLifecycle()

    LaunchedEffect(addAccount, folders, navigator) {
        addAccount.events.collect { event ->
            when (event) {
                is AddAccountEvent.Created -> {
                    folders.select(event.accountId)
                    navigator.open(Screen.Folders)
                }
            }
        }
    }
    BackHandler(enabled = screen != Screen.Folders) {
        addAccount.reset()
        navigator.back()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = screen) {
            Screen.Folders -> FoldersRoute(folders, navigator)
            is Screen.Inbox -> InboxRoute(current.scope, inbox, navigator)
            Screen.AddAccount -> AddAccountRoute(addAccount, navigator)
        }
    }
}

@Composable
private fun FoldersRoute(folders: FolderListViewModel, navigator: AppNavigator) {
    val state by folders.state.collectAsStateWithLifecycle()
    FolderListScreen(
        state = state,
        actions = FolderListActions(
            onSelectAccount = folders::select,
            onAddAccount = { navigator.open(Screen.AddAccount) },
            onOpenFolder = { accountId, path ->
                navigator.open(Screen.Inbox(InboxScope.Folder(accountId, path)))
            },
            onOpenUnified = { navigator.open(Screen.Inbox(InboxScope.Unified)) },
            onRequestRemoval = folders::requestRemoval,
            onDismissRemoval = folders::dismissRemoval,
            onConfirmRemoval = folders::confirmRemoval
        )
    )
}

@Composable
private fun InboxRoute(scope: InboxScope, inbox: InboxViewModel, navigator: AppNavigator) {
    val state by inbox.state.collectAsStateWithLifecycle()
    LaunchedEffect(scope) { inbox.show(scope) }
    InboxScreen(
        scope = scope,
        state = state,
        actions = InboxActions(
            onBack = { navigator.back() },
            onRefresh = inbox::refresh,
            onLoadMore = inbox::loadMore,
            onFilterChange = inbox::setFilter,
            // Reading a conversation arrives with T15.
            onOpenConversation = {},
            onScrolled = inbox::onScrolled,
            savedScroll = inbox::savedScroll
        )
    )
}

@Composable
private fun AddAccountRoute(addAccount: AddAccountViewModel, navigator: AppNavigator) {
    val state by addAccount.state.collectAsStateWithLifecycle()
    AddAccountScreen(
        state = state,
        actions = AddAccountActions(
            onBack = {
                addAccount.reset()
                navigator.back()
            },
            onTextChange = addAccount::onTextChange,
            onSecurityChange = addAccount::onSecurityChange,
            onAdvancedToggle = addAccount::onAdvancedToggle,
            onSubmit = addAccount::submit,
            onCancel = addAccount::cancel,
            onSignIn = addAccount::onSignInClick,
            onOAuthLaunched = addAccount::onOAuthLaunched,
            onOAuthResult = addAccount::onOAuthResult
        )
    )
}
