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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.folder.ShellScope
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.ui.account.AddAccountActions
import com.qtekfun.ultimatemail.ui.account.AddAccountEvent
import com.qtekfun.ultimatemail.ui.account.AddAccountScreen
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.account.ReauthActions
import com.qtekfun.ultimatemail.ui.account.ReauthEvent
import com.qtekfun.ultimatemail.ui.account.ReauthScreen
import com.qtekfun.ultimatemail.ui.account.ReauthViewModel
import com.qtekfun.ultimatemail.ui.backup.ExportRoute
import com.qtekfun.ultimatemail.ui.backup.ImportRoute
import com.qtekfun.ultimatemail.ui.compose.ComposeEntryEffects
import com.qtekfun.ultimatemail.ui.compose.ComposeScreens
import com.qtekfun.ultimatemail.ui.compose.ComposeStart
import com.qtekfun.ultimatemail.ui.compose.ComposerRoute
import com.qtekfun.ultimatemail.ui.compose.DraftsRoute
import com.qtekfun.ultimatemail.ui.compose.OutboxRoute
import com.qtekfun.ultimatemail.ui.conversation.ConversationRoute
import com.qtekfun.ultimatemail.ui.conversation.ConversationViewModel
import com.qtekfun.ultimatemail.ui.conversation.noticeText
import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.drawer.DrawerViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxActions
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.inbox.SelectionActions
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import com.qtekfun.ultimatemail.ui.nav.Screen
import com.qtekfun.ultimatemail.ui.picker.MovePickerHost
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
import com.qtekfun.ultimatemail.ui.shell.ShellCompose
import kotlinx.coroutines.flow.map

internal fun inboxActions(
    inbox: InboxViewModel,
    navigator: AppNavigator,
    onOpenSearch: () -> Unit
) = InboxActions(
    onOpenMenu = { navigator.setDrawerOpen(true) },
    onRefresh = inbox::refresh,
    onLoadMore = inbox::loadMore,
    onFilterChange = inbox::setFilter,
    onOpenConversation = {
        navigator.openConversation(it.accountId, it.folderPath, it.threadId)
    },
    onScrolled = inbox::onScrolled,
    savedScroll = inbox::savedScroll,
    selection = SelectionActions(
        onToggle = inbox::toggleSelection,
        onSwipe = inbox::onSwipe,
        onSelectAll = inbox::selectAll,
        onClear = inbox::clearSelection,
        onApply = inbox::applyToSelection,
        onMove = inbox::moveSelection,
        restoreRequests = inbox.restoreRequests
    ),
    onOpenSearch = onOpenSearch
)

internal fun drawerActions(
    drawer: DrawerViewModel,
    navigator: AppNavigator,
    show: (InboxScope) -> Unit
) = DrawerActions(
    onOpenUnified = { show(InboxScope.Unified) },
    onOpenFolder = { accountId, path -> show(InboxScope.Folder(accountId, path)) },
    onToggleFolder = drawer::toggleFolder,
    onToggleSection = drawer::toggleSection,
    onRefresh = drawer::refresh,
    onOpenDestination = navigator::open,
    onReauthenticate = navigator::openReauth
)
