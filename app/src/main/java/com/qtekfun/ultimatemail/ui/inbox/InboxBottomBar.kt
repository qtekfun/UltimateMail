// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.BulkAvailability
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.ui.components.BarIcons
import com.qtekfun.ultimatemail.ui.components.MailIcons

private val MinTouchTarget = 48.dp
private val BarElevation = 3.dp
private val BarShadow = 6.dp

/** What the floating bar needs to know: little, so it does not redraw with every list change. */
internal data class BarState(
    val filter: InboxFilter = InboxFilter.ALL,
    val selecting: Boolean = false,
    val selectedCount: Int = 0,
    val bulk: BulkAvailability? = null
)

/** What the buttons of the floating bar do. */
internal data class BarActions(
    val onFilterChange: (InboxFilter) -> Unit,
    val onSearch: () -> Unit,
    val onCompose: () -> Unit,
    val selection: SelectionActions
)

/**
 * The floating capsule over the bottom of the message list: filter, search and compose. While
 * rows are selected it holds the actions for them instead. Screen readers reach it after the
 * list, and it stays above the navigation bar and the keyboard (the caller pads it).
 */
@Composable
internal fun InboxBottomBar(state: BarState, actions: BarActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                traversalIndex = 1f
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = BarElevation,
        shadowElevation = BarShadow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state.selecting) {
                SelectionButtons(state, actions.selection)
            } else {
                BrowseButtons(state.filter, actions)
            }
        }
    }
}

@Composable
private fun RowScope.BrowseButtons(filter: InboxFilter, actions: BarActions) {
    FilterButton(filter, actions.onFilterChange)
    SearchField(actions.onSearch, Modifier.weight(1f))
    FilledIconButton(
        onClick = actions.onCompose,
        modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
    ) {
        Icon(BarIcons.Compose, contentDescription = stringResource(R.string.compose_fab))
    }
}

@Composable
private fun FilterButton(filter: InboxFilter, onChange: (InboxFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
        ) {
            Icon(
                if (filter == InboxFilter.ALL) BarIcons.Filter else BarIcons.FilterActive,
                contentDescription = stringResource(R.string.inbox_filter_button)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InboxFilter.entries.forEach { option ->
                FilterItem(option, option == filter) {
                    open = false
                    onChange(option)
                }
            }
        }
    }
}

@Composable
private fun FilterItem(option: InboxFilter, picked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(option.labelRes())) },
        trailingIcon = if (picked) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = onClick,
        modifier = Modifier.semantics { selected = picked }
    )
}

/** A read-only field: it only looks like one, a tap opens the real search screen. */
@Composable
private fun SearchField(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.search_open)
    Surface(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .widthIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false
            )
        }
    }
}

@Composable
private fun RowScope.SelectionButtons(state: BarState, actions: SelectionActions) {
    val bulk = state.bulk
    val any = state.selectedCount > 0
    MarkButton(bulk, any, actions)
    SelectionButton(
        MailIcons.Move,
        stringResource(R.string.inbox_bar_move),
        any && bulk?.canMove == true,
        actions.onMove
    )
    SelectionButton(
        MailIcons.Archive,
        stringResource(R.string.inbox_bar_archive),
        any && bulk?.canArchive == true
    ) { actions.onApply(RowChange.ARCHIVE) }
    SelectionButton(
        Icons.Filled.Delete,
        stringResource(R.string.inbox_bar_trash),
        any && bulk?.canDelete == true
    ) { actions.onApply(RowChange.DELETE) }
}

@Composable
private fun RowScope.SelectionButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = MinTouchTarget)
            .widthIn(min = MinTouchTarget)
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** "Mark": a menu with read or unread, and flag or unflag, as the selection calls for. */
@Composable
private fun RowScope.MarkButton(
    bulk: BulkAvailability?,
    enabled: Boolean,
    actions: SelectionActions
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = { open = true },
            enabled = enabled && bulk != null,
            modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
        ) {
            Icon(MailIcons.MarkRead, contentDescription = stringResource(R.string.inbox_bar_mark))
        }
        if (bulk != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf(bulk.readChange, bulk.starChange).forEach { change ->
                    DropdownMenuItem(
                        text = { Text(stringResource(change.labelRes())) },
                        onClick = {
                            open = false
                            actions.onApply(change)
                        }
                    )
                }
            }
        }
    }
}

internal fun InboxFilter.labelRes(): Int = when (this) {
    InboxFilter.ALL -> R.string.inbox_filter_all
    InboxFilter.UNREAD -> R.string.inbox_filter_unread
    InboxFilter.STARRED -> R.string.inbox_filter_starred
    InboxFilter.ATTACHMENTS -> R.string.inbox_filter_attachments
}

private fun RowChange.labelRes(): Int = when (this) {
    RowChange.MARK_READ -> R.string.inbox_action_mark_read
    RowChange.MARK_UNREAD -> R.string.conversation_mark_unread
    RowChange.STAR -> R.string.conversation_star
    RowChange.UNSTAR -> R.string.conversation_unstar
    RowChange.ARCHIVE -> R.string.conversation_archive
    RowChange.DELETE -> R.string.conversation_delete
}
