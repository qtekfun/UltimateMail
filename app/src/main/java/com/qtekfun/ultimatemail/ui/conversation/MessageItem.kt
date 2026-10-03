// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.conversation.MessageView
import com.qtekfun.ultimatemail.domain.conversation.RecipientSummary
import com.qtekfun.ultimatemail.domain.inbox.MessageTimeFormatter
import com.qtekfun.ultimatemail.ui.components.Avatar
import com.qtekfun.ultimatemail.ui.components.MailIcons
import com.qtekfun.ultimatemail.ui.theme.starColor

private val MinTouchTarget = 48.dp
private val SmallIcon = 16.dp

/** What one message of the conversation can do. */
data class MessageActions(
    val onToggle: () -> Unit,
    val onToggleDetails: () -> Unit,
    val body: BodyActions,
    val onAttachment: (id: Long, action: AttachmentAction) -> Unit
)

/** A message of the conversation: one line when collapsed, header, body and attachments open. */
@Composable
fun MessageItem(
    message: MessageView,
    formatter: MessageTimeFormatter,
    actions: MessageActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (message.expanded) {
            ExpandedHeader(message, formatter, actions)
            MessageBodyContent(message, actions.body)
            AttachmentList(message.attachments, actions.onAttachment)
        } else {
            CollapsedHeader(message, formatter, actions.onToggle)
        }
        HorizontalDivider()
    }
}

@Composable
private fun CollapsedHeader(
    message: MessageView,
    formatter: MessageTimeFormatter,
    onToggle: () -> Unit
) {
    val time = formatter.format(message.sentAt)
    val unread = stringResource(R.string.conversation_desc_unread)
    val description = buildString {
        if (message.unread) append(unread).append(", ")
        append(message.sender).append(", ").append(formatter.formatSpoken(message.sentAt))
        if (message.snippet.isNotBlank()) append(", ").append(message.snippet.trim())
    }
    val collapsed = stringResource(R.string.message_state_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.message_expand), onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = collapsed
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(name = message.senderName, address = message.senderAddress, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = message.snippet.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Indicators(message)
        Text(
            text = time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExpandedHeader(
    message: MessageView,
    formatter: MessageTimeFormatter,
    actions: MessageActions
) {
    val expanded = stringResource(R.string.message_state_expanded)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.message_collapse),
                    onClick = actions.onToggle
                )
                .semantics { stateDescription = expanded },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Avatar(name = message.senderName, address = message.senderAddress)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (message.senderName.isNotBlank()) {
                    Text(
                        text = message.senderAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatter.formatSpoken(message.sentAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Indicators(message)
            }
        }
        RecipientsLine(message, actions.onToggleDetails)
        if (message.detailsShown) Details(message, formatter)
    }
}

/** "to me, Ana" with a chevron that opens the full list of recipients. */
@Composable
private fun RecipientsLine(message: MessageView, onToggleDetails: () -> Unit) {
    val summary = recipientsText(message.recipients)
    val action = stringResource(
        if (message.detailsShown) R.string.message_hide_details else R.string.message_show_details
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClickLabel = action, onClick = onToggleDetails)
            .padding(start = 52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = if (message.detailsShown) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = if (message.detailsShown) MailIcons.ExpandLess else MailIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun recipientsText(summary: RecipientSummary): String {
    val names = summary.names.joinToString(", ")
    val listed = if (summary.hiddenCount > 0) {
        pluralStringResource(
            R.plurals.message_more_recipients,
            summary.hiddenCount,
            names,
            summary.hiddenCount
        )
    } else {
        names
    }
    return when {
        summary.onlyMe -> stringResource(R.string.message_to_me)
        summary.includesMe -> stringResource(R.string.message_to_me_and, listed)
        else -> stringResource(R.string.message_to_names, listed)
    }
}

@Composable
private fun Details(message: MessageView, formatter: MessageTimeFormatter) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DetailRow(R.string.message_label_from, listOf(message.senderAddress))
        DetailRow(R.string.message_label_to, message.to)
        DetailRow(R.string.message_label_cc, message.cc)
        DetailRow(R.string.message_label_date, listOf(formatter.formatSpoken(message.sentAt)))
    }
}

@Composable
private fun DetailRow(@StringRes label: Int, values: List<String>) {
    if (values.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            values.forEach { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The star and pending-sync icons; the row or header around them says the same in words. */
@Composable
private fun Indicators(message: MessageView) {
    val pending = stringResource(R.string.conversation_desc_pending)
    val starred = stringResource(R.string.conversation_desc_flagged)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (message.pendingSync) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = pending,
                modifier = Modifier.size(SmallIcon),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        if (message.flagged) {
            Icon(
                Icons.Filled.Star,
                contentDescription = starred,
                modifier = Modifier.size(SmallIcon),
                tint = starColor()
            )
        }
    }
}
