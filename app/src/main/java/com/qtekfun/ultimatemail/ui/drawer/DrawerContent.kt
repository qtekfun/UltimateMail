// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.folder.AccountSection
import com.qtekfun.ultimatemail.domain.folder.FolderListItem
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.ui.components.MailIcons
import com.qtekfun.ultimatemail.ui.nav.Screen

private val MinTouchTarget = 48.dp

/** What the side menu can do. */
data class DrawerActions(
    val onOpenUnified: () -> Unit,
    val onOpenFolder: (accountId: Long, path: String) -> Unit,
    val onToggleFolder: (accountId: Long, path: String) -> Unit,
    val onToggleSection: (accountId: Long) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenDestination: (Screen) -> Unit,
    val onReauthenticate: (accountId: Long) -> Unit
)

/**
 * The side menu as a mailboxes screen: "All inboxes" and the Inbox of each account, the special
 * mailboxes of the selected account (and the Outbox while something waits in it), one section
 * per account with its folders and labels (closed until opened), and at the bottom the sync
 * status and Settings. The row showing is highlighted ([shown]).
 */
@Composable
fun DrawerContent(
    state: FolderMenuState,
    shown: InboxScope?,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    /** Messages waiting in the outbox; the entry is only there while there are some. */
    outboxCount: Int = 0
) {
    val account = state.selected ?: return
    val menu = state.mailboxes
    ModalDrawerSheet(modifier = modifier, drawerContainerColor = sheetColor()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = "title") {
                Text(
                    stringResource(R.string.drawer_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 32.dp, top = 24.dp, end = 16.dp)
                )
            }
            inboxCard(state, shown, actions)
            specialCard(state, account, shown, actions, outboxCount)
            if (menu.sections.isNotEmpty()) {
                item(key = "accounts-header") {
                    CardHeader(stringResource(R.string.drawer_section_accounts))
                }
            }
            menu.sections.forEach { section -> accountCard(section, shown, actions) }
            item(key = "end-space") { Spacer(Modifier.padding(8.dp)) }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        DrawerFooter(state.syncLine, account.id, actions)
    }
}

private fun LazyListScope.inboxCard(
    state: FolderMenuState,
    shown: InboxScope?,
    actions: DrawerActions
) {
    val inboxes = state.mailboxes.inboxes
    if (inboxes.size == 1) {
        // One account: "All inboxes" and its Inbox are the same list, so there is a single row.
        val inbox = inboxes.single()
        item(key = "inbox:" + inbox.account.id) {
            MailboxRow(
                label = stringResource(R.string.folder_inbox),
                icon = MailIcons.Inbox,
                selected = shown == inbox.scope,
                onClick = { actions.onOpenFolder(inbox.scope.accountId, inbox.scope.path) },
                position = CardPosition.Single,
                modifier = Modifier.padding(top = 12.dp),
                count = inbox.unread
            )
        }
        return
    }
    val size = inboxes.size + 1
    item(key = "unified") {
        MailboxRow(
            label = stringResource(R.string.drawer_all_inboxes),
            icon = Icons.Filled.Email,
            selected = shown == InboxScope.Unified,
            onClick = actions.onOpenUnified,
            position = CardPosition.of(0, size),
            modifier = Modifier.padding(top = 12.dp),
            count = state.mailboxes.unifiedUnread
        )
    }
    inboxes.forEachIndexed { index, inbox ->
        item(key = "inbox:" + inbox.account.id) {
            MailboxRow(
                label = inbox.account.email,
                icon = MailIcons.Inbox,
                selected = shown == inbox.scope,
                onClick = { actions.onOpenFolder(inbox.scope.accountId, inbox.scope.path) },
                position = CardPosition.of(index + 1, size),
                count = inbox.unread
            )
        }
    }
}

private fun LazyListScope.specialCard(
    state: FolderMenuState,
    account: AccountSummary,
    shown: InboxScope?,
    actions: DrawerActions,
    outboxCount: Int
) {
    val special = state.mailboxes.special
    val size = special.size + if (outboxCount > 0) 1 else 0
    if (size == 0) return
    if (state.accounts.size > 1) {
        item(key = "special-header") { CardHeader(account.email) }
    } else {
        item(key = "special-space") { Spacer(Modifier.padding(6.dp)) }
    }
    special.forEachIndexed { index, folder ->
        item(key = "special:" + folder.path) {
            FolderRow(folder, RowEnv(account.id, shown, actions), CardPosition.of(index, size))
        }
    }
    if (outboxCount > 0) {
        item(key = "outbox") {
            MailboxRow(
                label = stringResource(R.string.drawer_outbox),
                icon = Icons.AutoMirrored.Filled.Send,
                selected = false,
                onClick = { actions.onOpenDestination(Screen.Outbox) },
                position = CardPosition.of(size - 1, size),
                count = outboxCount,
                countDescription = pluralStringResource(
                    R.plurals.drawer_outbox_count,
                    outboxCount,
                    outboxCount
                )
            )
        }
    }
}

private fun LazyListScope.accountCard(
    section: AccountSection,
    shown: InboxScope?,
    actions: DrawerActions
) {
    val id = section.account.id
    val size = section.folders.size + 1
    item(key = "section:$id") {
        val toggle = { actions.onToggleSection(id) }
        MailboxRow(
            label = section.account.email,
            icon = Icons.Filled.AccountCircle,
            selected = false,
            onClick = toggle,
            position = CardPosition.of(0, size),
            modifier = Modifier.padding(top = 8.dp),
            expanded = section.open,
            onToggle = toggle,
            toggleDescription = stringResource(
                if (section.open) R.string.drawer_collapse else R.string.drawer_expand,
                section.account.email
            )
        )
    }
    section.folders.forEachIndexed { index, folder ->
        item(key = "folder:$id:" + folder.path) {
            FolderRow(
                folder,
                RowEnv(id, shown, actions),
                CardPosition.of(index + 1, size),
                section.expanded
            )
        }
    }
}

/** What a folder row needs to know about the account it is listed under. */
private class RowEnv(val accountId: Long, val shown: InboxScope?, val actions: DrawerActions)

@Composable
private fun FolderRow(
    folder: FolderListItem,
    env: RowEnv,
    position: CardPosition,
    expanded: Set<String> = emptySet()
) {
    val name = folder.role.displayName() ?: folder.name
    val selected = folder.selectable && env.shown == InboxScope.Folder(env.accountId, folder.path)
    MailboxRow(
        label = name,
        icon = folder.role.icon(folder.isLabel),
        selected = selected,
        position = position,
        indent = folder.depth,
        count = folder.unread,
        expanded = if (folder.hasChildren) folder.path in expanded else null,
        onToggle = { env.actions.onToggleFolder(env.accountId, folder.path) },
        onClick = {
            if (folder.selectable) {
                env.actions.onOpenFolder(env.accountId, folder.path)
            } else {
                env.actions.onToggleFolder(env.accountId, folder.path)
            }
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
