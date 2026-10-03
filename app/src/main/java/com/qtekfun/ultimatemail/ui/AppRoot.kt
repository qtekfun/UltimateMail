// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.folder.ShellScope
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.ui.account.AddAccountActions
import com.qtekfun.ultimatemail.ui.account.AddAccountEvent
import com.qtekfun.ultimatemail.ui.account.AddAccountScreen
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.conversation.ConversationRoute
import com.qtekfun.ultimatemail.ui.conversation.ConversationViewModel
import com.qtekfun.ultimatemail.ui.conversation.messageRes
import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.drawer.DrawerViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxActions
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import com.qtekfun.ultimatemail.ui.nav.Screen
import com.qtekfun.ultimatemail.ui.search.SearchActions
import com.qtekfun.ultimatemail.ui.search.SearchScreen
import com.qtekfun.ultimatemail.ui.search.SearchViewModel
import com.qtekfun.ultimatemail.ui.settings.AccountSettingsActions
import com.qtekfun.ultimatemail.ui.settings.AccountSettingsScreen
import com.qtekfun.ultimatemail.ui.settings.AccountSettingsViewModel
import com.qtekfun.ultimatemail.ui.settings.SettingsActions
import com.qtekfun.ultimatemail.ui.settings.SettingsScreen
import com.qtekfun.ultimatemail.ui.settings.SettingsViewModel
import com.qtekfun.ultimatemail.ui.shell.MainShell
import com.qtekfun.ultimatemail.ui.shell.NoAccountsScreen
import com.qtekfun.ultimatemail.ui.shell.ShellActions
import kotlinx.coroutines.launch

/** Shows the current [Screen] and connects each screen to its view model. */
@Suppress("LongParameterList") // One view model per screen; they are created by the activity.
@Composable
fun AppRoot(
    navigator: AppNavigator,
    drawer: DrawerViewModel,
    addAccount: AddAccountViewModel,
    inbox: InboxViewModel,
    conversation: ConversationViewModel,
    settings: SettingsViewModel,
    accountSettings: AccountSettingsViewModel,
    search: SearchViewModel
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

            is Screen.Conversation -> ConversationRoute(current, conversation, navigator::back)

            // One call site for every screen with the side menu, so the menu keeps its state
            // (and its closing animation) while the folder changes.
            Screen.Home, is Screen.Inbox ->
                ShellRoute((current as? Screen.Inbox)?.scope, navigator, drawer, inbox, search)

            is Screen.Search -> SearchRoute(search, navigator)

            Screen.Settings -> SettingsRoute(settings, navigator)

            is Screen.AccountSettings ->
                AccountSettingsRoute(current.accountId, accountSettings, navigator)
        }
        // Messages about what was done (archived, deleted, with Undo) outlive the screen.
        NoticeHost(conversation)
    }
}

/** Shows the snackbar for the notices of [conversation], whichever screen is on. */
@Composable
private fun NoticeHost(conversation: ConversationViewModel) {
    val notice by conversation.notice.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        val result = host.showSnackbar(
            message = resources.getString(current.kind.messageRes()),
            actionLabel = if (current.undoable) resources.getString(R.string.notice_undo) else null,
            duration = if (current.undoable) SnackbarDuration.Long else SnackbarDuration.Short
        )
        when {
            !current.undoable -> conversation.noticeShown(current.id)
            result == SnackbarResult.ActionPerformed -> conversation.undo(current.id)
            else -> conversation.commit(current.id)
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host, modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ShellRoute(
    requested: InboxScope?,
    navigator: AppNavigator,
    drawer: DrawerViewModel,
    inbox: InboxViewModel,
    search: SearchViewModel
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
                onOpenConversation = {
                    navigator.openConversation(it.accountId, it.folderPath, it.threadId)
                },
                onScrolled = inbox::onScrolled,
                savedScroll = inbox::savedScroll,
                onOpenSearch = {
                    val from = SearchScope.startingFrom(scope)
                    search.startNew(from)
                    navigator.openSearch(from)
                }
            )
        )
    )
}

@Composable
private fun SearchRoute(search: SearchViewModel, navigator: AppNavigator) {
    val state by search.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        actions = SearchActions(
            onBack = { navigator.back() },
            onTextChange = search::onTextChange,
            onSubmit = search::submit,
            onScope = search::setScope,
            onToggleUnread = search::toggleUnread,
            onToggleStarred = search::toggleStarred,
            onToggleAttachments = search::toggleAttachments,
            onDate = search::setDate,
            onLoadMore = search::loadMore,
            onOpen = {
                search.onOpened()
                navigator.openConversation(it.accountId, it.folderPath, it.threadId)
            },
            onServerSearch = search::searchOnServer,
            onCancelServer = search::cancelServerSearch,
            onUseRecent = search::useRecent,
            onRemoveRecent = search::removeRecent,
            onClearRecent = search::clearRecent
        )
    )
}

@Composable
private fun SettingsRoute(settings: SettingsViewModel, navigator: AppNavigator) {
    val state by settings.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            onBack = { navigator.back() },
            onThemeChange = settings::setTheme,
            onDensityChange = settings::setDensity,
            onDynamicColorChange = settings::setDynamicColor,
            onAmoledChange = settings::setAmoled,
            onSwipeRightChange = settings::setSwipeRight,
            onSwipeLeftChange = settings::setSwipeLeft,
            onRemoteContentChange = settings::setRemoteContent,
            onOpenAccount = { navigator.open(Screen.AccountSettings(it)) }
        )
    )
}

@Composable
private fun AccountSettingsRoute(
    accountId: Long,
    viewModel: AccountSettingsViewModel,
    navigator: AppNavigator
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountId) { viewModel.show(accountId) }
    // The account was removed from here: nothing is left to show.
    LaunchedEffect(state.loaded, state.found) {
        if (state.loaded && !state.found) navigator.open(Screen.Home)
    }
    AccountSettingsScreen(
        state = state,
        actions = AccountSettingsActions(
            onBack = { navigator.back() },
            onNameChange = viewModel::onNameChange,
            onSignatureChange = viewModel::onSignatureChange,
            onSignatureEnabledChange = viewModel::onSignatureEnabledChange,
            onBeforeQuoteChange = viewModel::onBeforeQuoteChange,
            onSave = viewModel::save,
            onOfflineWindowChange = viewModel::onOfflineWindowChange,
            onFolderSyncChange = viewModel::onFolderSyncChange,
            onRequestRemoval = viewModel::requestRemoval,
            onDismissRemoval = viewModel::dismissRemoval,
            onConfirmRemoval = viewModel::confirmRemoval
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
