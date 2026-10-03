// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.compose.DraftListItem
import com.qtekfun.ultimatemail.domain.compose.ServerDraft
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter

private val MinTouchTarget = 48.dp

/** What the Drafts list can do. */
data class DraftsActions(
    val onOpenMenu: () -> Unit,
    val onOpen: (Long) -> Unit,
    val onOpenServerDraft: (ServerDraft) -> Unit,
    val onRequestDelete: (Long) -> Unit,
    val onDismissDelete: () -> Unit,
    val onConfirmDelete: () -> Unit
)

/**
 * The Drafts folder as the side menu shows it (RF-07): the drafts written on this device and the
 * copies saved on the server. Tapping a local draft opens the composer on it; its overflow menu
 * deletes it, after asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    title: String,
    state: DraftsUiState,
    actions: DraftsActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onOpenMenu,
                        modifier = Modifier.heightIn(min = MinTouchTarget)
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.drawer_open)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loaded && state.items.isEmpty()) {
                EmptyMessage(R.string.drafts_empty_title, R.string.drafts_empty_body)
            } else {
                DraftList(state, actions)
            }
        }
    }
    state.confirmingDelete?.let { DeleteDialog(actions) }
}

@Composable
private fun DraftList(state: DraftsUiState, actions: DraftsActions) {
    val formatter = rememberMessageTimeFormatter()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.items, key = { it.key() }) { item ->
            when (item) {
                is DraftListItem.Local -> {
                    val draft = item.draft
                    DraftRow(
                        subject = draft.subject,
                        line = recipientsLine(draft.recipients.size, draft.recipients.firstOrNull()),
                        time = formatter.format(item.time),
                        onClick = { actions.onOpen(draft.id) },
                        onDelete = { actions.onRequestDelete(draft.id) }
                    )
                }

                is DraftListItem.OnServer -> DraftRow(
                    subject = item.draft.subject,
                    line = stringResource(R.string.drafts_on_server),
                    time = formatter.format(item.time),
                    onClick = { actions.onOpenServerDraft(item.draft) },
                    onDelete = null
                )
            }
            HorizontalDivider()
        }
    }
}

private fun DraftListItem.key(): String = when (this) {
    is DraftListItem.Local -> "local:" + draft.id
    is DraftListItem.OnServer -> "server:" + draft.messageRowId
}

@Composable
internal fun recipientsLine(count: Int, first: MailAddress?): String {
    if (first == null) return stringResource(R.string.drafts_no_recipient)
    val name = first.name?.takeIf { it.isNotBlank() } ?: first.address
    return if (count > 1) {
        stringResource(R.string.outbox_to_more, name, count - 1)
    } else {
        stringResource(R.string.outbox_to, name)
    }
}

@Composable
private fun DraftRow(
    subject: String,
    line: String,
    time: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                subject.ifBlank { stringResource(R.string.drafts_no_subject) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onDelete != null) {
            Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.drafts_more)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drafts_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(actions: DraftsActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissDelete,
        title = { Text(stringResource(R.string.drafts_delete_title)) },
        text = { Text(stringResource(R.string.drafts_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDelete,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) { Text(stringResource(R.string.drafts_delete_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onDismissDelete,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
internal fun EmptyMessage(title: Int, body: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
    }
}
