// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.ui.components.MailIcons

private val MinTouchTarget = 48.dp

/**
 * The bar that replaces the normal one while conversations are selected (T16): the count, which
 * a screen reader hears again whenever it changes, and the actions for the selection. Archive,
 * delete, read and star are buttons; move and select all are in the overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(state: InboxState, actions: SelectionActions, modifier: Modifier = Modifier) {
    val bulk = state.bulk
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        title = {
            Text(
                pluralStringResource(
                    R.plurals.inbox_selection_count,
                    state.selection.count,
                    state.selection.count
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        },
        navigationIcon = {
            BarButton(Icons.Filled.Close, stringResource(R.string.inbox_selection_close)) {
                actions.onClear()
            }
        },
        actions = {
            if (bulk.canArchive) {
                BarButton(MailIcons.Archive, stringResource(R.string.conversation_archive)) {
                    actions.onApply(RowChange.ARCHIVE)
                }
            }
            if (bulk.canDelete) {
                BarButton(Icons.Filled.Delete, stringResource(R.string.conversation_delete)) {
                    actions.onApply(RowChange.DELETE)
                }
            }
            val read = bulk.readChange == RowChange.MARK_READ
            BarButton(
                if (read) MailIcons.MarkRead else Icons.Filled.Email,
                stringResource(
                    if (read) R.string.inbox_action_mark_read else R.string.conversation_mark_unread
                )
            ) { actions.onApply(bulk.readChange) }
            val star = bulk.starChange == RowChange.STAR
            BarButton(
                if (star) Icons.Filled.Star else MailIcons.StarOutline,
                stringResource(
                    if (star) R.string.conversation_star else R.string.conversation_unstar
                )
            ) { actions.onApply(bulk.starChange) }
            OverflowMenu(canMove = bulk.canMove, actions = actions)
        }
    )
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun OverflowMenu(canMove: Boolean, actions: SelectionActions) {
    var open by remember { mutableStateOf(false) }
    BarButton(Icons.Filled.MoreVert, stringResource(R.string.inbox_selection_more)) { open = true }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (canMove) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.inbox_action_move)) },
                leadingIcon = { Icon(MailIcons.Move, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onMove()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.inbox_select_all)) },
            leadingIcon = { Icon(MailIcons.SelectAll, contentDescription = null) },
            onClick = {
                open = false
                actions.onSelectAll()
            }
        )
    }
}
