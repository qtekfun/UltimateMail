// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.qtekfun.ultimatemail.ui.components.rememberMessageTimeFormatter

/** What the conversation screen can do. */
data class ConversationScreenActions(
    val onBack: () -> Unit,
    val onToggleStar: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onMove: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onComposeNew: () -> Unit,
    val onCompose: (ComposeMode) -> Unit,
    val onToggleMessage: (Long) -> Unit,
    val onToggleDetails: (Long) -> Unit,
    val onToggleQuoted: (Long) -> Unit,
    val onAllowRemote: (Long) -> Unit,
    val onToggleOriginalColors: (Long) -> Unit,
    val onRetryBody: (Long) -> Unit,
    val onAttachment: (id: Long, action: AttachmentAction) -> Unit
)

/**
 * One conversation, oldest message first, the first unread one (or the newest) open and the
 * others folded to a line. The toolbar acts on the whole conversation; the reply buttons on its
 * newest message.
 */
@Composable
fun ConversationScreen(
    state: ConversationState,
    actions: ConversationScreenActions,
    modifier: Modifier = Modifier
) {
    val view = state.view
    Scaffold(
        modifier = modifier,
        topBar = { ConversationTopBar(state, actions) },
        bottomBar = { if (view != null) ConversationBottomBar(view, actions) }
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
                        onAllowRemote = { actions.onAllowRemote(message.id) },
                        onToggleOriginalColors = { actions.onToggleOriginalColors(message.id) }
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
