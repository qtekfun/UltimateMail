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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.ui.components.MailIcons

private val MinTouchTarget = 48.dp

/** What the composer screen can do. */
data class ComposerActions(
    val onClose: () -> Unit,
    val onSend: () -> Unit,
    val onAttach: () -> Unit,
    val onRequestDiscard: () -> Unit,
    val onSelectSender: (Long) -> Unit,
    val onInput: (RecipientKind, String) -> Unit,
    val onCommit: (RecipientKind) -> Unit,
    val onPickSuggestion: (RecipientKind, MailAddress) -> Unit,
    val onRemoveChip: (RecipientKind, Int) -> Unit,
    val onShowCcBcc: () -> Unit,
    val onSubject: (String) -> Unit,
    val onBody: (String) -> Unit,
    val onRemoveAttachment: (Long) -> Unit,
    val onDismissMessage: () -> Unit,
    val onConfirm: () -> Unit,
    val onDismissDialog: () -> Unit
)

/**
 * The composer (RF-07): a top bar with close, attach and send, the From selector, recipients as
 * chips with suggestions, Subject, a plain-text body that grows with the text, and the
 * attachments. The keyboard pushes the content up (`imePadding`) and the page scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(state: ComposerState, actions: ComposerActions, modifier: Modifier = Modifier) {
    val editing = state.phase == ComposerPhase.EDITING
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.kind.titleRes())) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onClose,
                        modifier = Modifier.heightIn(min = MinTouchTarget)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.composer_close)
                        )
                    }
                },
                actions = { if (editing) ComposerMenu(actions) }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state.phase) {
                ComposerPhase.EDITING -> ComposerForm(state, actions)

                ComposerPhase.GONE -> CenteredText(R.string.composer_gone)

                ComposerPhase.LOADING, ComposerPhase.FINISHED ->
                    CenteredText(R.string.composer_loading)
            }
        }
    }
    state.dialog?.let { ComposerDialogs(it, actions) }
}

private fun DraftKind.titleRes(): Int = when (this) {
    DraftKind.NEW -> R.string.compose_title_new
    DraftKind.REPLY -> R.string.compose_reply
    DraftKind.REPLY_ALL -> R.string.compose_reply_all
    DraftKind.FORWARD -> R.string.compose_forward
}

@Composable
private fun CenteredText(res: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ComposerMenu(actions: ComposerActions) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = actions.onAttach, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Icon(MailIcons.Attachment, contentDescription = stringResource(R.string.composer_attach))
    }
    IconButton(onClick = actions.onSend, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.composer_send)
        )
    }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = MinTouchTarget)
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.composer_more)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_discard)) },
                onClick = {
                    open = false
                    actions.onRequestDiscard()
                }
            )
        }
    }
}

@Composable
private fun ComposerForm(state: ComposerState, actions: ComposerActions) {
    // A placeholder is gone once there is text: the field would lose its name for a screen reader.
    val subjectName = stringResource(R.string.composer_subject)
    val bodyName = stringResource(R.string.composer_body)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        state.message?.let { MessageBanner(it, actions.onDismissMessage) }
        SenderRow(state, actions.onSelectSender)
        HorizontalDivider()
        RecipientSection(state, actions)
        TextField(
            value = state.subject,
            onValueChange = actions.onSubject,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = subjectName },
            placeholder = { Text(subjectName) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            colors = plainFieldColors()
        )
        HorizontalDivider()
        TextField(
            value = state.body,
            onValueChange = actions.onBody,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BODY_MIN_HEIGHT)
                .semantics { contentDescription = bodyName },
            placeholder = { Text(bodyName) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default
            ),
            colors = plainFieldColors()
        )
        AttachmentsSection(state, actions.onRemoveAttachment)
    }
}

private val BODY_MIN_HEIGHT = 240.dp

@Composable
private fun plainFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

@Composable
private fun SenderRow(state: ComposerState, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.senders.firstOrNull { it.id == state.senderId }
    val canChange = state.senders.size > 1
    val description = stringResource(R.string.composer_from_pick, current?.email.orEmpty())
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .let {
                    if (canChange) {
                        it.clickable(
                            onClickLabel = description,
                            role = Role.DropdownList,
                            onClick = { open = true }
                        )
                    } else {
                        it
                    }
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.composer_from),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                current?.email.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.senders.forEach { sender ->
                DropdownMenuItem(
                    text = { Text(sender.email) },
                    onClick = {
                        open = false
                        onSelect(sender.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun RecipientSection(state: ComposerState, actions: ComposerActions) {
    val expanded = state.showCcBcc || !state.cc.chips.isEmpty() || !state.bcc.chips.isEmpty()
    RecipientRow(
        kind = RecipientKind.TO,
        state = state,
        actions = actions,
        trailing = if (expanded) null else actions.onShowCcBcc
    )
    if (expanded) {
        RecipientRow(RecipientKind.CC, state, actions)
        RecipientRow(RecipientKind.BCC, state, actions)
    }
}

@Composable
private fun MessageBanner(message: ComposerMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        ComposerMessage.NoRecipients -> stringResource(R.string.composer_msg_no_recipients)

        ComposerMessage.InvalidRecipient -> stringResource(R.string.composer_msg_invalid_recipient)

        ComposerMessage.AttachmentMissing ->
            stringResource(R.string.composer_msg_attachment_missing)

        is ComposerMessage.AttachmentTooLarge ->
            stringResource(
                R.string.composer_msg_attachment_too_large,
                formatSize(message.limitBytes)
            )

        ComposerMessage.AttachmentUnreadable ->
            stringResource(R.string.composer_msg_attachment_unreadable)

        ComposerMessage.DraftGone -> stringResource(R.string.composer_msg_draft_gone)
    }
    Banner(text, error = true, onDismiss = onDismiss)
}

@Composable
private fun ComposerDialogs(dialog: ComposerDialog, actions: ComposerActions) {
    val (title, body, confirm) = when (dialog) {
        ComposerDialog.EMPTY_SUBJECT -> Triple(
            R.string.composer_empty_subject_title,
            R.string.composer_empty_subject_body,
            R.string.composer_send_anyway
        )

        ComposerDialog.EMPTY_BODY -> Triple(
            R.string.composer_empty_body_title,
            R.string.composer_empty_body_body,
            R.string.composer_send_anyway
        )

        ComposerDialog.DISCARD -> Triple(
            R.string.composer_discard_title,
            R.string.composer_discard_body,
            R.string.composer_discard_confirm
        )
    }
    AlertDialog(
        onDismissRequest = actions.onDismissDialog,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirm,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onDismissDialog,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
