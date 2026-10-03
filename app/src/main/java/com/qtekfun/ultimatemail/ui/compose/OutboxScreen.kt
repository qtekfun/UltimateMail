// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.compose.OutboxReason

private val MinTouchTarget = 48.dp

/** What the Outbox can do. */
data class OutboxScreenActions(
    val onBack: () -> Unit,
    val onRetry: (Long) -> Unit,
    val onEdit: (Long) -> Unit,
    val onRequestDiscard: (Long) -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onDismissPrompt: () -> Unit
)

/** The text for why a message is waiting or failed; [failed] picks the wording of "other". */
@StringRes
internal fun OutboxReason.textRes(failed: Boolean): Int = when (this) {
    OutboxReason.NETWORK -> R.string.outbox_reason_network

    OutboxReason.TIMEOUT -> R.string.outbox_reason_timeout

    OutboxReason.AUTH_REQUIRED -> R.string.outbox_reason_auth_required

    OutboxReason.CERTIFICATE -> R.string.outbox_reason_certificate

    OutboxReason.SERVER_REJECTED -> R.string.outbox_reason_server_rejected

    OutboxReason.SERVER_BUSY -> R.string.outbox_reason_server_busy

    OutboxReason.CONFIRM_SENT -> R.string.outbox_reason_confirm_sent

    OutboxReason.ATTACHMENT_MISSING -> R.string.outbox_reason_attachment_missing

    OutboxReason.INTERNAL -> R.string.outbox_reason_internal

    OutboxReason.OTHER ->
        if (failed) R.string.outbox_reason_other_failed else R.string.outbox_reason_other_waiting
}

/**
 * The Outbox (RF-07): what is queued, being sent or failed, each with its state and, when it
 * went wrong, why in words. Failed messages offer Retry, Edit and Discard; one that may already
 * have gone out says so instead of letting it be sent twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    state: OutboxUiState,
    actions: OutboxScreenActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbox_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.heightIn(min = MinTouchTarget)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.outbox_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loaded && state.rows.isEmpty()) {
                EmptyMessage(R.string.outbox_empty_title, R.string.outbox_empty_body)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.draftId }) { row ->
                        OutboxItem(row, actions)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    state.prompt?.let { OutboxPromptDialog(it, actions) }
}

@Composable
private fun OutboxItem(row: OutboxRow, actions: OutboxScreenActions) {
    val failed = row.status is OutboxRowStatus.Failed
    val statusText = when (row.status) {
        is OutboxRowStatus.Waiting -> stringResource(R.string.outbox_status_waiting)
        OutboxRowStatus.Sending -> stringResource(R.string.outbox_status_sending)
        is OutboxRowStatus.Failed -> stringResource(R.string.outbox_status_failed)
    }
    val reasonText = when (val status = row.status) {
        is OutboxRowStatus.Waiting -> status.reason?.let { stringResource(it.textRes(false)) }
        OutboxRowStatus.Sending -> null
        is OutboxRowStatus.Failed -> stringResource(status.reason.textRes(true))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            row.subject.ifBlank { stringResource(R.string.drafts_no_subject) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val first = row.recipient
        if (first != null) {
            Text(
                if (row.recipientCount > 1) {
                    stringResource(R.string.outbox_to_more, first, row.recipientCount - 1)
                } else {
                    stringResource(R.string.outbox_to, first)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelLarge,
            color = with(MaterialTheme.colorScheme) { if (failed) error else primary }
        )
        if (reasonText != null) {
            Text(
                reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (failed) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            ActionButton(R.string.outbox_retry) { actions.onRetry(row.draftId) }
            ActionButton(R.string.outbox_edit) { actions.onEdit(row.draftId) }
            ActionButton(R.string.outbox_discard) { actions.onRequestDiscard(row.draftId) }
        }
    }
}

@Composable
private fun ActionButton(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget)) {
        Text(stringResource(label))
    }
}

@Composable
private fun OutboxPromptDialog(prompt: OutboxPrompt, actions: OutboxScreenActions) {
    when (prompt) {
        OutboxPrompt.MaybeSent -> AlertDialog(
            onDismissRequest = actions.onDismissPrompt,
            title = { Text(stringResource(R.string.outbox_maybe_sent_title)) },
            text = { Text(stringResource(R.string.outbox_maybe_sent_body)) },
            confirmButton = {
                TextButton(
                    onClick = actions.onDismissPrompt,
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) { Text(stringResource(R.string.composer_dismiss)) }
            }
        )

        is OutboxPrompt.ConfirmDiscard -> AlertDialog(
            onDismissRequest = actions.onDismissPrompt,
            title = { Text(stringResource(R.string.outbox_discard_title)) },
            text = { Text(stringResource(R.string.outbox_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = actions.onConfirmDiscard,
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) { Text(stringResource(R.string.outbox_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = actions.onDismissPrompt,
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
