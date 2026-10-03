// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ConversationView
import com.qtekfun.ultimatemail.ui.components.LabelChipRow
import com.qtekfun.ultimatemail.ui.components.MailIcons
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter
import com.qtekfun.ultimatemail.ui.theme.StarColor

private val MinTouchTarget = 48.dp

/** What the conversation screen can do. */
data class ConversationScreenActions(
    val onBack: () -> Unit,
    val onToggleStar: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onCompose: (ComposeMode) -> Unit,
    val onToggleMessage: (Long) -> Unit,
    val onToggleDetails: (Long) -> Unit,
    val onToggleQuoted: (Long) -> Unit,
    val onAllowRemote: (Long) -> Unit,
    val onRetryBody: (Long) -> Unit,
    val onAttachment: (id: Long, action: AttachmentAction) -> Unit
)

/**
 * One conversation, oldest message first, the first unread one (or the newest) open and the
 * others folded to a line. The toolbar acts on the whole conversation; the reply buttons on its
 * newest message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    state: ConversationState,
    actions: ConversationScreenActions,
    modifier: Modifier = Modifier
) {
    val view = state.view
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
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
                },
                actions = { if (view != null) ToolbarActions(view, actions) }
            )
        },
        bottomBar = { if (view != null) ReplyBar(actions.onCompose) }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Loading()
                view == null -> Missing()
                else -> MessageThread(view, actions)
            }
        }
    }
}

@Composable
private fun ToolbarActions(view: ConversationView, actions: ConversationScreenActions) {
    val starred = view.newest?.flagged == true
    IconButton(onClick = actions.onToggleStar, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Icon(
            imageVector = if (starred) Icons.Filled.Star else MailIcons.StarOutline,
            contentDescription = stringResource(
                if (starred) R.string.conversation_unstar else R.string.conversation_star
            ),
            tint = if (starred) StarColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    IconButton(onClick = actions.onMarkUnread, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Icon(
            Icons.Filled.MailOutline,
            contentDescription = stringResource(R.string.conversation_mark_unread)
        )
    }
    if (view.targets.canArchive) {
        IconButton(
            onClick = actions.onArchive,
            modifier = Modifier.heightIn(min = MinTouchTarget)
        ) {
            Icon(
                MailIcons.Archive,
                contentDescription = stringResource(R.string.conversation_archive)
            )
        }
    }
    if (view.targets.canDelete) {
        IconButton(onClick = actions.onDelete, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.conversation_delete)
            )
        }
    }
}

@Composable
private fun Loading() {
    val description = stringResource(R.string.conversation_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = description }
        )
    }
}

@Composable
private fun Missing() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.conversation_missing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.conversation_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageThread(view: ConversationView, actions: ConversationScreenActions) {
    val formatter = rememberMessageTimeFormatter()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Subject(view)
        HorizontalDivider()
        view.messages.forEach { message ->
            MessageItem(
                message = message,
                formatter = formatter,
                actions = MessageActions(
                    onToggle = { actions.onToggleMessage(message.id) },
                    onToggleDetails = { actions.onToggleDetails(message.id) },
                    body = BodyActions(
                        onRetry = { actions.onRetryBody(message.id) },
                        onToggleQuoted = { actions.onToggleQuoted(message.id) },
                        onAllowRemote = { actions.onAllowRemote(message.id) }
                    ),
                    onAttachment = actions.onAttachment
                )
            )
        }
    }
}

/** The subject in large type with the labels of the conversation as chips under it. */
@Composable
private fun Subject(view: ConversationView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = view.subject.ifBlank { stringResource(R.string.conversation_no_subject) },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        if (!view.labels.isEmpty) LabelChipRow(view.labels)
    }
}

/** Reply, reply all and forward are always in reach at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyBar(onCompose: (ComposeMode) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReplyButton(MailIcons.Reply, R.string.compose_reply) { onCompose(ComposeMode.REPLY) }
            ReplyButton(MailIcons.ReplyAll, R.string.compose_reply_all) {
                onCompose(ComposeMode.REPLY_ALL)
            }
            ReplyButton(MailIcons.Forward, R.string.compose_forward) {
                onCompose(ComposeMode.FORWARD)
            }
        }
    }
}

@Composable
private fun ReplyButton(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}
