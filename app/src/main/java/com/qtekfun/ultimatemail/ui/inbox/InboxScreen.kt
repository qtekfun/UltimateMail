// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.inbox.MessageTimeFormatter
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.domain.inbox.SwipeDecision
import com.qtekfun.ultimatemail.domain.inbox.SwipeDirection
import com.qtekfun.ultimatemail.domain.inbox.SwipePlanner
import com.qtekfun.ultimatemail.ui.components.ConversationRow
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter
import com.qtekfun.ultimatemail.ui.components.rememberReduceMotion
import com.qtekfun.ultimatemail.ui.theme.LocalRowAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private val MinTouchTarget = 48.dp

/** Items from the end at which the next page starts loading. */
private const val PREFETCH_DISTANCE = 10

/** Lets the list reuse the composition of a row that scrolled out for the next one. */
private const val ROW_TYPE = "conversation"

/** What the conversation list screen can do. */
data class InboxActions(
    val onOpenMenu: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadMore: () -> Unit,
    val onFilterChange: (InboxFilter) -> Unit,
    val onOpenConversation: (ConversationItem) -> Unit,
    val onScrolled: (index: Int, offset: Int) -> Unit,
    val savedScroll: () -> ScrollPosition,
    val selection: SelectionActions,
    /** The sync progress or last sync time shown under the title. */
    val syncLine: StateFlow<SyncLine>,
    /** Opens the search (T20) in the scope of the list shown. */
    val onOpenSearch: () -> Unit = {}
)

/** What the list does with swipes and the selection (T16). */
data class SelectionActions(
    /** Long-press, or a tap while selecting: picks the row or takes it off. */
    val onToggle: (ConversationItem) -> Unit,
    /** A row was swiped; true when it leaves the list, false to spring it back. */
    val onSwipe: (ConversationItem, SwipeDirection) -> Boolean,
    /** "Edit": selection mode with nothing picked yet. */
    val onEdit: () -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
    val onApply: (RowChange) -> Unit,
    val onMove: () -> Unit,
    /** Rows (by key) to bring back because their swipe could not be applied. */
    val restoreRequests: Flow<String>
)

/** The conversations of a folder or of the unified inbox, with pull-to-refresh and paging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    scope: InboxScope,
    state: InboxState,
    actions: InboxActions,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The state can still belong to the previous scope for a frame after navigating.
    val ready = state.loaded && state.scope == scope
    // The first list on screen is what the user waits for when the app starts (T22).
    val activity = LocalActivity.current
    LaunchedEffect(ready) { if (ready) activity?.reportFullyDrawn() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // An empty list cannot be scrolled to bring the large title back, so it starts expanded.
    val nothingToScroll = ready && state.conversations.isEmpty()
    LaunchedEffect(nothingToScroll) {
        if (nothingToScroll) scrollBehavior.state.heightOffset = 0f
    }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val selecting = ready && state.selection.active
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) {
                SelectionTopBar(state.selection.count, actions.selection)
            } else {
                InboxTopBar(
                    scope,
                    TitleInfo(state.takeIf { ready }, actions.syncLine),
                    actions,
                    scrollBehavior
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (ready) {
                val below = with(LocalDensity.current) { barHeightPx.toDp() }
                InboxContent(state, actions, below)
            } else {
                Loading()
            }
            // After the list in the tree: a screen reader gets to it once the list is done.
            InboxBottomBar(
                state = barState(state, selecting),
                actions = BarActions(
                    onFilterChange = actions.onFilterChange,
                    onSearch = actions.onOpenSearch,
                    onCompose = onCompose,
                    selection = actions.selection
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .onSizeChanged { barHeightPx = it.height }
            )
        }
    }
}

private fun barState(state: InboxState, selecting: Boolean) = BarState(
    filter = state.filter,
    selecting = selecting,
    selectedCount = if (selecting) state.selection.count else 0,
    bulk = state.bulk.takeIf { selecting }
)

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
private fun InboxContent(state: InboxState, actions: InboxActions, bottomPadding: Dp) {
    val indent = LocalRowAppearance.current.textIndent
    val listState = rememberInboxListState(state, actions)
    val refreshLabel = stringResource(R.string.inbox_refresh_action)
    val formatter = rememberMessageTimeFormatter()
    val hiddenLabels = remember(state.folderName) { setOfNotNull(state.folderName) }
    val empty = state.empty
    val reduceMotion = rememberReduceMotion()
    val restoreTokens = rememberRestoreTokens(actions.selection)

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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            if (empty != null) {
                item(key = "empty") {
                    Box(modifier = Modifier.fillParentMaxSize()) {
                        EmptyState(empty, state.filter, actions.onFilterChange)
                    }
                }
            } else if (state.conversations.isEmpty()) {
                item(key = "loading-more") { Box(Modifier.fillParentMaxSize()) { Loading() } }
            }
            items(state.conversations, key = { it.key }, contentType = { ROW_TYPE }) { item ->
                InboxRow(
                    item = item,
                    state = state,
                    actions = actions,
                    view = RowView(
                        formatter,
                        hiddenLabels,
                        restoreTokens[item.key] ?: 0,
                        reduceMotion
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(start = indent))
            }
        }
    }
}

/** The scroll state of the list, remembered per scope, reporting scrolls and asking for pages. */
@Composable
private fun rememberInboxListState(state: InboxState, actions: InboxActions): LazyListState {
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
    return listState
}

/** Per row key, how many times its swipe was refused: a change brings the row back. */
@Composable
private fun rememberRestoreTokens(selection: SelectionActions): Map<String, Int> {
    val tokens = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(selection) {
        selection.restoreRequests.collect { key -> tokens[key] = (tokens[key] ?: 0) + 1 }
    }
    return tokens
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

        InboxEmpty.NOT_ENABLED ->
            R.string.inbox_empty_not_enabled_title to
                R.string.inbox_empty_not_enabled_body

        InboxEmpty.NO_MESSAGES -> R.string.inbox_empty_none_title to R.string.inbox_empty_none_body

        InboxEmpty.FILTERED_OUT -> filteredEmptyText(filter)
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

private fun filteredEmptyText(filter: InboxFilter): Pair<Int, Int> = when (filter) {
    InboxFilter.STARRED ->
        R.string.inbox_empty_starred_title to R.string.inbox_empty_starred_body

    InboxFilter.ATTACHMENTS ->
        R.string.inbox_empty_attachments_title to R.string.inbox_empty_attachments_body

    InboxFilter.ALL, InboxFilter.UNREAD ->
        R.string.inbox_empty_filtered_title to R.string.inbox_empty_filtered_body
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
