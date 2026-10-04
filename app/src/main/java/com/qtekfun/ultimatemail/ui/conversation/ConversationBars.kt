// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ConversationView
import com.qtekfun.ultimatemail.domain.conversation.FolderLabel
import com.qtekfun.ultimatemail.ui.components.MailIcons
import com.qtekfun.ultimatemail.ui.drawer.displayName
import com.qtekfun.ultimatemail.ui.theme.starColor

private val MinTouchTarget = 48.dp
private val BackLabelMaxWidth = 112.dp

/**
 * The top bar of the reading screen: back with the name of the mailbox, the arrows to the
 * previous and next conversation of the list, and the overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationTopBar(state: ConversationState, actions: ConversationScreenActions) {
    val view = state.view
    TopAppBar(
        title = {},
        navigationIcon = { BackWithLabel(view?.folder, actions.onBack) },
        actions = {
            if (view != null) {
                ToolbarButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = R.string.conversation_previous,
                    enabled = state.neighbours.previous != null,
                    onClick = actions.onPrevious
                )
                ToolbarButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = R.string.conversation_next,
                    enabled = state.neighbours.next != null,
                    onClick = actions.onNext
                )
                MoreMenu(view, actions)
            }
        }
    )
}

@Composable
private fun BackWithLabel(folder: FolderLabel?, onBack: () -> Unit) {
    val label = folder?.let { it.role.displayName() ?: it.name }
    val description = label?.let { stringResource(R.string.conversation_back_to, it) }
        ?: stringResource(R.string.action_back)
    Row(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = BackLabelMaxWidth)
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    @StringRes description: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = MinTouchTarget)
    ) {
        Icon(icon, contentDescription = stringResource(description), tint = tint)
    }
}

/** The rarer actions of the conversation: star and mark as unread. */
@Composable
private fun MoreMenu(view: ConversationView, actions: ConversationScreenActions) {
    var open by remember { mutableStateOf(false) }
    val starred = view.newest?.flagged == true
    Box {
        ToolbarButton(Icons.Filled.MoreVert, R.string.conversation_more, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (starred) R.string.conversation_unstar else R.string.conversation_star
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (starred) Icons.Filled.Star else MailIcons.StarOutline,
                        contentDescription = null,
                        tint = if (starred) starColor() else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    open = false
                    actions.onToggleStar()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversation_mark_unread)) },
                leadingIcon = { Icon(Icons.Filled.MailOutline, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onMarkUnread()
                }
            )
        }
    }
}

/**
 * The bottom bar: archive and trash when the account has them, move, reply (with the choice of
 * reply all and forward) and a new message. It wraps to a second row when the font is huge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationBottomBar(view: ConversationView, actions: ConversationScreenActions) {
    Surface(tonalElevation = 2.dp) {
        FlowRow(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.Center
        ) {
            if (view.targets.canArchive) {
                ToolbarButton(MailIcons.Archive, R.string.conversation_archive, actions.onArchive)
            }
            if (view.targets.canDelete) {
                ToolbarButton(Icons.Filled.Delete, R.string.conversation_delete, actions.onDelete)
            }
            ToolbarButton(MailIcons.Move, R.string.inbox_action_move, actions.onMove)
            ReplyMenu(actions.onCompose)
            ToolbarButton(Icons.Filled.Edit, R.string.compose_fab, actions.onComposeNew)
        }
    }
}

@Composable
private fun ReplyMenu(onCompose: (ComposeMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(MailIcons.Reply, R.string.conversation_reply_menu, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReplyItem(MailIcons.Reply, R.string.compose_reply) {
                open = false
                onCompose(ComposeMode.REPLY)
            }
            ReplyItem(MailIcons.ReplyAll, R.string.compose_reply_all) {
                open = false
                onCompose(ComposeMode.REPLY_ALL)
            }
            ReplyItem(MailIcons.Forward, R.string.compose_forward) {
                open = false
                onCompose(ComposeMode.FORWARD)
            }
        }
    }
}

@Composable
private fun ReplyItem(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}
