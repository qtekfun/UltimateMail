// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.search.DateFilter
import com.qtekfun.ultimatemail.domain.search.SearchHighlights
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure
import com.qtekfun.ultimatemail.ui.components.ConversationRow
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter
import com.qtekfun.ultimatemail.ui.theme.LocalRowAppearance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private val MinTouchTarget = 48.dp

/** Lets the list reuse the composition of a row that scrolled out for the next one. */
private const val ROW_TYPE = "conversation"

/** Items from the end at which the next page starts loading. */
private const val PREFETCH_DISTANCE = 10

/** What the search screen can do. */
data class SearchActions(
    val onBack: () -> Unit,
    val onTextChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onScope: (SearchScope) -> Unit,
    val onToggleUnread: () -> Unit,
    val onToggleStarred: () -> Unit,
    val onToggleAttachments: () -> Unit,
    val onDate: (DateFilter) -> Unit,
    val onLoadMore: () -> Unit,
    val onOpen: (ConversationItem) -> Unit,
    val onServerSearch: () -> Unit,
    val onCancelServer: () -> Unit,
    val onUseRecent: (String) -> Unit,
    val onRemoveRecent: (String) -> Unit,
    val onClearRecent: () -> Unit
)

/** Search (RF-09): the field opens focused, with the scope and filter chips and the results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchState, actions: SearchActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { SearchField(state.text, actions) },
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
            SearchChips(state, actions)
            SearchBody(state, actions)
        }
    }
}

/** The text field; it takes the focus (and so the keyboard) when the screen opens empty. */
@Composable
private fun SearchField(text: String, actions: SearchActions) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.search_field_hint)
    LaunchedEffect(Unit) {
        if (text.isEmpty()) focus.requestFocus()
    }
    TextField(
        value = text,
        onValueChange = actions.onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .focusRequester(focus)
            .semantics { contentDescription = hint },
        placeholder = { Text(hint) },
        singleLine = true,
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(
                    onClick = { actions.onTextChange("") },
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.search_clear)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                actions.onSubmit()
                keyboard?.hide()
            }
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun SearchBody(state: SearchState, actions: SearchActions) {
    val indent = LocalRowAppearance.current.textIndent
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, state, actions.onLoadMore)
    val formatter = rememberMessageTimeFormatter()
    val highlights = remember(state.query) { SearchHighlights(state.query) }
    val onServer = stringResource(R.string.search_server_badge)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        when (state.phase) {
            SearchPhase.IDLE -> recentItems(state, actions)

            SearchPhase.SEARCHING -> item(key = "searching") { Searching() }

            SearchPhase.ERROR -> item(key = "error") {
                Message(R.string.search_error_title, R.string.search_error_body)
            }

            SearchPhase.NONE_FOUND -> item(key = "none") {
                Message(R.string.search_none_title, R.string.search_none_body)
            }

            SearchPhase.RESULTS -> Unit
        }
        items(state.results, key = { it.key }, contentType = { ROW_TYPE }) { item ->
            ConversationRow(
                item = item,
                formatter = formatter,
                onClick = { actions.onOpen(item) },
                accountMarker = state.markers[item.accountId],
                highlights = highlights
            )
            HorizontalDivider(modifier = Modifier.padding(start = indent))
        }
        if (state.canSearchServer) {
            item(key = "server") { ServerPart(state, actions) }
        }
        if (state.serverResults.isNotEmpty()) {
            item(key = "server-title") {
                Text(
                    stringResource(R.string.search_server_section),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(state.serverResults, key = {
                "server-" + it.key
            }, contentType = { ROW_TYPE }) { item ->
                ConversationRow(
                    item = item,
                    formatter = formatter,
                    onClick = { actions.onOpen(item) },
                    accountMarker = state.markers[item.accountId],
                    highlights = highlights,
                    badge = onServer
                )
                HorizontalDivider(modifier = Modifier.padding(start = indent))
            }
        }
    }
}

/** Asks for another page when the list nears its end. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, state: SearchState, onLoadMore: () -> Unit) {
    LaunchedEffect(listState, state.results.size, state.hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - PREFETCH_DISTANCE
        }.distinctUntilChanged().filter { it }.collect { if (state.hasMore) onLoadMore() }
    }
}

private fun LazyListScope.recentItems(state: SearchState, actions: SearchActions) {
    if (state.recent.isEmpty()) {
        item(key = "idle") { Message(R.string.search_idle_title, R.string.search_idle_hint) }
        return
    }
    item(key = "recent-title") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.search_recent_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(
                onClick = actions.onClearRecent,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Text(stringResource(R.string.search_recent_clear))
            }
        }
    }
    items(state.recent, key = { "recent-$it" }) { text ->
        val use = stringResource(R.string.search_recent_use, text)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable { actions.onUseRecent(text) }
                .semantics { contentDescription = use }
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(
                onClick = { actions.onRemoveRecent(text) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.search_recent_remove, text)
                )
            }
        }
    }
}

@Composable
private fun Searching() {
    val description = stringResource(R.string.search_searching)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = description
            }
        )
    }
}

@Composable
private fun Message(title: Int, body: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    }
}
