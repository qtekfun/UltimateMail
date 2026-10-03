// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.folder.FolderListItem
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.ui.nav.Screen
import java.util.Date

private val MinTouchTarget = 48.dp
private val IndentPerLevel = 16.dp

/** What the side menu can do. */
data class DrawerActions(
    val onSelectAccount: (Long) -> Unit,
    val onAddAccount: () -> Unit,
    val onOpenUnified: () -> Unit,
    val onOpenFolder: (accountId: Long, path: String) -> Unit,
    val onToggleFolder: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onRequestRemoval: () -> Unit,
    val onDismissRemoval: () -> Unit,
    val onConfirmRemoval: () -> Unit,
    val onOpenDestination: (Screen) -> Unit
)

/**
 * The side menu: the account header, the unified inbox, the folders of the selected account
 * (special ones first, then the tree) with the one showing highlighted ([shown]), and at the
 * bottom the sync status and the links of [DrawerDestinations].
 */
@Composable
fun DrawerContent(
    state: FolderMenuState,
    shown: InboxScope?,
    actions: DrawerActions,
    modifier: Modifier = Modifier
) {
    val account = state.selected ?: return
    ModalDrawerSheet(modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = "header") { AccountHeader(state, account, actions) }
            item(key = "unified") {
                DrawerRow(
                    label = stringResource(R.string.inbox_unified),
                    icon = Icons.Filled.Email,
                    selected = shown == InboxScope.Unified,
                    onClick = actions.onOpenUnified
                )
            }
            item(key = "divider-top") { DrawerDivider() }
            if (state.special.isEmpty() && state.folders.isEmpty()) {
                item(key = "empty") { NoFolders() }
            }
            items(state.special, key = { "special:" + it.path }) { folder ->
                FolderRow(folder, account, shown, state.expanded, actions)
            }
            if (state.special.isNotEmpty() && state.folders.isNotEmpty()) {
                item(key = "divider-folders") { DrawerDivider() }
            }
            items(state.folders, key = { "folder:" + it.path }) { folder ->
                FolderRow(folder, account, shown, state.expanded, actions)
            }
        }
        DrawerDivider()
        DrawerFooter(state.syncLine, actions)
    }
    if (state.confirmingRemoval) {
        RemoveAccountDialog(actions.onDismissRemoval, actions.onConfirmRemoval)
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
}

@Composable
private fun AccountHeader(state: FolderMenuState, account: AccountSummary, actions: DrawerActions) {
    var open by remember { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.account_switch)
    Box(modifier = Modifier.padding(top = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(onClickLabel = switchLabel) { open = true }
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { other ->
                DropdownMenuItem(
                    text = { Text(other.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        actions.onSelectAccount(other.id)
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_add)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onAddAccount()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_remove)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onRequestRemoval()
                }
            )
        }
    }
}

@Composable
private fun NoFolders() {
    Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.folders_empty_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.folders_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FolderRow(
    folder: FolderListItem,
    account: AccountSummary,
    shown: InboxScope?,
    expanded: Set<String>,
    actions: DrawerActions
) {
    val name = folder.role.displayName() ?: folder.name
    val selected = folder.selectable && shown == InboxScope.Folder(account.id, folder.path)
    val open = folder.path in expanded
    DrawerRow(
        label = name,
        icon = folder.role.icon(folder.isLabel),
        selected = selected,
        indent = folder.depth,
        unread = folder.unread,
        expandable = folder.hasChildren,
        expanded = open,
        onToggle = { actions.onToggleFolder(folder.path) },
        onClick = {
            if (folder.selectable) {
                actions.onOpenFolder(account.id, folder.path)
            } else {
                actions.onToggleFolder(folder.path)
            }
        }
    )
}

@Composable
private fun DrawerRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    indent: Int = 0,
    unread: Int = 0,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggle: () -> Unit = {}
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        badge = if (unread > 0 || expandable) {
            { RowTrailing(label, unread, expandable, expanded, onToggle) }
        } else {
            null
        },
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(start = IndentPerLevel * indent)
            .heightIn(min = MinTouchTarget)
    )
}

@Composable
private fun RowTrailing(
    label: String,
    unread: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (unread > 0) {
            val description = pluralStringResource(R.plurals.drawer_unread_count, unread, unread)
            Text(
                text = unread.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { contentDescription = description }
            )
        }
        if (expandable) {
            IconButton(onClick = onToggle, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.drawer_collapse else R.string.drawer_expand,
                        label
                    )
                )
            }
        }
    }
}

@Composable
private fun DrawerFooter(syncLine: SyncLine, actions: DrawerActions) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = syncLine.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = actions.onRefresh,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.drawer_sync_now)
                )
            }
        }
        DrawerDestinations.footer.forEach { destination ->
            DrawerRow(
                label = stringResource(destination.label),
                icon = destination.icon,
                selected = false,
                onClick = { actions.onOpenDestination(destination.screen) }
            )
        }
    }
}

@Composable
private fun SyncLine.text(): String = when (this) {
    SyncLine.NeverSynced -> stringResource(R.string.sync_status_never)

    SyncLine.Syncing -> stringResource(R.string.sync_status_syncing)

    is SyncLine.LastSynced -> {
        val context = LocalContext.current
        stringResource(
            R.string.sync_status_last,
            DateFormat.getTimeFormat(context).format(Date.from(at))
        )
    }

    SyncLine.SignInAgain -> stringResource(R.string.sync_status_reauth)

    is SyncLine.Failed -> stringResource(
        when (problem) {
            SyncProblem.NETWORK -> R.string.sync_error_network
            SyncProblem.TIMEOUT -> R.string.sync_error_timeout
            SyncProblem.CERTIFICATE -> R.string.sync_error_certificate
            SyncProblem.SERVER, SyncProblem.PROTOCOL -> R.string.sync_error_server
            SyncProblem.UNKNOWN -> R.string.sync_error_unknown
        }
    )
}

@Composable
internal fun RemoveAccountDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
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
