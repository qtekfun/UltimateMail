// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.domain.folder.ShellScope
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.ui.account.AddAccountActions
import com.qtekfun.ultimatemail.ui.account.AddAccountEvent
import com.qtekfun.ultimatemail.ui.account.AddAccountScreen
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.drawer.DrawerViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxActions
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import com.qtekfun.ultimatemail.ui.nav.Screen
import com.qtekfun.ultimatemail.ui.shell.MainShell
import com.qtekfun.ultimatemail.ui.shell.NoAccountsScreen
import com.qtekfun.ultimatemail.ui.shell.ShellActions
import kotlinx.coroutines.launch

/** Shows the current [Screen] and connects each screen to its view model. */
@Composable
fun AppRoot(
    navigator: AppNavigator,
    drawer: DrawerViewModel,
    addAccount: AddAccountViewModel,
    inbox: InboxViewModel
) {
    val screen by navigator.screen.collectAsStateWithLifecycle()
    val menuOpen by navigator.drawerOpen.collectAsStateWithLifecycle()

    LaunchedEffect(addAccount, drawer, navigator) {
        addAccount.events.collect { event ->
            when (event) {
                is AddAccountEvent.Created -> {
                    navigator.open(Screen.Inbox(drawer.switchAccount(event.accountId)))
                }
            }
        }
    }
    // Back closes the menu first, then returns to the start screen, then leaves the app.
    BackHandler(enabled = screen != Screen.Home || menuOpen) {
        if (screen == Screen.AddAccount) addAccount.reset()
        navigator.back()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = screen) {
            Screen.AddAccount -> AddAccountRoute(addAccount, navigator)

            // One call site for every screen with the side menu, so the menu keeps its state
            // (and its closing animation) while the folder changes.
            Screen.Home, is Screen.Inbox, Screen.Settings ->
                // T21: Settings is not reachable yet; it will get its own branch.
                ShellRoute((current as? Screen.Inbox)?.scope, navigator, drawer, inbox)
        }
    }
}

@Composable
private fun ShellRoute(
    requested: InboxScope?,
    navigator: AppNavigator,
    drawer: DrawerViewModel,
    inbox: InboxViewModel
) {
    val menu by drawer.state.collectAsStateWithLifecycle()
    val menuOpen by navigator.drawerOpen.collectAsStateWithLifecycle()
    val inboxState by inbox.state.collectAsStateWithLifecycle()
    val coroutines = rememberCoroutineScope()

    // Nothing is drawn until Room answered, so the empty state does not flash on start.
    if (!menu.loaded) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val scope = ShellScope.resolve(requested, menu.accounts, menu.defaultScope)
    if (scope == null) {
        NoAccountsScreen(onAddAccount = { navigator.open(Screen.AddAccount) })
        return
    }
    LaunchedEffect(scope) {
        inbox.show(scope)
        drawer.onScopeShown(scope)
    }
    val show = { target: InboxScope -> navigator.showScope(target, menu.defaultScope) }
    MainShell(
        scope = scope,
        menu = menu,
        menuOpen = menuOpen,
        inboxState = inboxState,
        actions = ShellActions(
            onMenuOpenChange = navigator::setDrawerOpen,
            drawer = DrawerActions(
                onSelectAccount = { id -> coroutines.launch { show(drawer.switchAccount(id)) } },
                onAddAccount = { navigator.open(Screen.AddAccount) },
                onOpenUnified = { show(InboxScope.Unified) },
                onOpenFolder = { accountId, path -> show(InboxScope.Folder(accountId, path)) },
                onToggleFolder = drawer::toggleFolder,
                onRefresh = drawer::refresh,
                onRequestRemoval = drawer::requestRemoval,
                onDismissRemoval = drawer::dismissRemoval,
                onConfirmRemoval = drawer::confirmRemoval,
                onOpenDestination = navigator::open
            ),
            inbox = InboxActions(
                onOpenMenu = { navigator.setDrawerOpen(true) },
                onRefresh = inbox::refresh,
                onLoadMore = inbox::loadMore,
                onFilterChange = inbox::setFilter,
                // Reading a conversation arrives with T15.
                onOpenConversation = {},
                onScrolled = inbox::onScrolled,
                savedScroll = inbox::savedScroll
            )
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
