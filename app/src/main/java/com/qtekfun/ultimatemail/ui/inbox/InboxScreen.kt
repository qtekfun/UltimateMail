// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.ui.components.ConversationRow
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter
import com.qtekfun.ultimatemail.ui.folders.displayName
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private val MinTouchTarget = 48.dp
private val DividerIndent = 68.dp

/** Items from the end at which the next page starts loading. */
private const val PREFETCH_DISTANCE = 10

/** What the conversation list screen can do. */
data class InboxActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit,
    val onFilterChange: (InboxFilter) -> Unit,
    val onOpenConversation: (ConversationItem) -> Unit,
    val onScrolled: (index: Int, offset: Int) -> Unit,
    val savedScroll: () -> ScrollPosition
)

/** The conversations of a folder or of the unified inbox, with pull-to-refresh and paging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    scope: InboxScope,
    state: InboxState,
    actions: InboxActions,
    modifier: Modifier = Modifier
) {
    // The state can still belong to the previous scope for a frame after navigating.
    val ready = state.loaded && state.scope == scope
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { InboxTitle(scope, state.takeIf { ready }) },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (ready) {
                FilterChip(
                    selected = state.filter == InboxFilter.UNREAD,
                    onClick = {
                        actions.onFilterChange(
                            if (state.filter ==
                                InboxFilter.UNREAD
                            ) {
                                InboxFilter.ALL
                            } else {
                                InboxFilter.UNREAD
                            }
                        )
                    },
                    label = { Text(stringResource(R.string.inbox_filter_unread)) },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .heightIn(min = MinTouchTarget)
                )
            }
            if (ready) {
                InboxContent(state, actions)
            } else {
                Loading()
            }
        }
    }
}

@Composable
private fun InboxTitle(scope: InboxScope, state: InboxState?) {
    val title = when (scope) {
        InboxScope.Unified -> stringResource(R.string.inbox_unified)

        is InboxScope.Folder ->
            state?.folderRole?.displayName() ?: state?.folderName
                ?: stringResource(R.string.app_name)
    }
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = state?.accountEmail
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Loading() {
    val description = stringResource(R.string.inbox_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = description
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxContent(state: InboxState, actions: InboxActions) {
    // A new state per scope: the list starts where the user left it (see InboxViewModel).
    val listState = remember(state.scope) {
        val saved = actions.savedScroll()
        LazyListState(saved.index, saved.offset)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) -> actions.onScrolled(index, offset) }
    }
    LaunchedEffect(listState, state.conversations.size, state.hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - PREFETCH_DISTANCE
        }.filter { it }.collect { if (state.hasMore) actions.onLoadMore() }
    }

    val refreshLabel = stringResource(R.string.inbox_refresh_action)
    val formatter = rememberMessageTimeFormatter()
    val hiddenLabels = remember(state.folderName) { setOfNotNull(state.folderName) }
    val empty = state.empty

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            // Pulling down is not possible with a screen reader, so offer the same as an action.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(refreshLabel) {
                        actions.onRefresh()
                        true
                    }
                )
            }
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (empty != null) {
                item(key = "empty") {
                    Box(modifier = Modifier.fillParentMaxSize()) {
                        EmptyState(empty, state.filter, actions.onFilterChange)
                    }
                }
            } else if (state.conversations.isEmpty()) {
                item(key = "loading-more") { Box(Modifier.fillParentMaxSize()) { Loading() } }
            }
            items(state.conversations, key = { it.key }) { item ->
                ConversationRow(
                    item = item,
                    formatter = formatter,
                    onClick = { actions.onOpenConversation(item) },
                    accountMarker = state.markers[item.accountId],
                    hiddenLabels = hiddenLabels
                )
                HorizontalDivider(modifier = Modifier.padding(start = DividerIndent))
            }
        }
    }
}

@Composable
private fun EmptyState(
    reason: InboxEmpty,
    filter: InboxFilter,
    onFilterChange: (InboxFilter) -> Unit
) {
    val (title, body) = when (reason) {
        InboxEmpty.NOT_SYNCED ->
            R.string.inbox_empty_unsynced_title to
                R.string.inbox_empty_unsynced_body

        InboxEmpty.NO_MESSAGES -> R.string.inbox_empty_none_title to R.string.inbox_empty_none_body

        InboxEmpty.FILTERED_OUT ->
            R.string.inbox_empty_filtered_title to
                R.string.inbox_empty_filtered_body
    }
    CenteredMessage(title, body) {
        if (reason == InboxEmpty.FILTERED_OUT && filter != InboxFilter.ALL) {
            Button(
                onClick = { onFilterChange(InboxFilter.ALL) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Text(stringResource(R.string.inbox_show_all))
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    @StringRes title: Int,
    @StringRes body: Int,
    action: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action()
    }
}
