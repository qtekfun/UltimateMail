// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.folders

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.folder.FolderListItem

private val MinTouchTarget = 48.dp
private val IndentPerLevel = 16.dp

/** What the folder screen can do. */
data class FolderListActions(
    val onSelectAccount: (Long) -> Unit,
    val onAddAccount: () -> Unit,
    val onOpenFolder: (accountId: Long, path: String) -> Unit,
    val onOpenUnified: () -> Unit,
    val onRequestRemoval: () -> Unit,
    val onDismissRemoval: () -> Unit,
    val onConfirmRemoval: () -> Unit
)

/** Accounts and the folders of the selected one, as stored on the device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    state: FolderListState,
    actions: FolderListActions,
    modifier: Modifier = Modifier
) {
    // Nothing is drawn until Room answered, so the empty state does not flash on start.
    if (!state.loaded) {
        Box(modifier = modifier.fillMaxSize())
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AccountSwitcher(state, actions) },
                actions = {
                    if (state.selected != null) {
                        IconButton(
                            onClick = actions.onRequestRemoval,
                            modifier = Modifier.heightIn(min = MinTouchTarget)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.account_remove)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.selected == null -> NoAccounts(actions.onAddAccount)

                state.folders.isEmpty() -> NoFolders()

                else -> FolderList(state.folders) {
                    actions.onOpenFolder(state.selected.id, it.path)
                }
            }
        }
    }
    if (state.confirmingRemoval) {
        RemoveAccountDialog(actions.onDismissRemoval, actions.onConfirmRemoval)
    }
}

@Composable
private fun AccountSwitcher(state: FolderListState, actions: FolderListActions) {
    val selected = state.selected
    if (selected == null) {
        Text(stringResource(R.string.app_name))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = MinTouchTarget)
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    selected.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    selected.email,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.account_switch)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        actions.onSelectAccount(account.id)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.inbox_unified)) },
                onClick = {
                    open = false
                    actions.onOpenUnified()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_add)) },
                onClick = {
                    open = false
                    actions.onAddAccount()
                }
            )
        }
    }
}

@Composable
private fun NoAccounts(onAddAccount: () -> Unit) {
    CenteredMessage(
        title = R.string.home_empty,
        body = R.string.home_empty_body
    ) {
        Button(onClick = onAddAccount, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.account_add))
        }
    }
}

@Composable
private fun NoFolders() {
    CenteredMessage(title = R.string.folders_empty_title, body = R.string.folders_empty_body) {}
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
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        action()
    }
}

@Composable
private fun FolderList(folders: List<FolderListItem>, onOpen: (FolderListItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(folders, key = { it.path }) { folder -> FolderRow(folder) { onOpen(folder) } }
    }
}

@Composable
private fun FolderRow(folder: FolderListItem, onClick: () -> Unit) {
    val tap = onClick
    val name = folder.role.displayName() ?: folder.name
    val description = when {
        folder.unread > 0 ->
            pluralStringResource(
                R.plurals.folder_unread_description,
                folder.unread,
                name,
                folder.unread
            )

        folder.isLabel -> stringResource(R.string.folder_label_description, name)

        else -> name
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(start = 16.dp + IndentPerLevel * folder.depth, end = 16.dp)
            // One announcement per row instead of the name and the badge separately.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    tap()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (folder.unread > 0) {
            Text(
                text = folder.unread.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Localized name of a special folder; other folders show the name the server gave them. */
@Composable
internal fun FolderRole.displayName(): String? {
    val res = when (this) {
        FolderRole.INBOX -> R.string.folder_inbox
        FolderRole.DRAFTS -> R.string.folder_drafts
        FolderRole.SENT -> R.string.folder_sent
        FolderRole.ARCHIVE -> R.string.folder_archive
        FolderRole.TRASH -> R.string.folder_trash
        FolderRole.JUNK -> R.string.folder_junk
        FolderRole.ALL_MAIL -> R.string.folder_all_mail
        FolderRole.STARRED -> R.string.folder_starred
        FolderRole.OTHER -> return null
    }
    return stringResource(res)
}

@Composable
private fun RemoveAccountDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_remove_title)) },
        text = { Text(stringResource(R.string.account_remove_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.account_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
