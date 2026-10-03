// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.AccountMarker
import com.qtekfun.ultimatemail.domain.inbox.ConversationDescriber
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.DescriptionTexts
import com.qtekfun.ultimatemail.domain.inbox.LabelPresentation
import com.qtekfun.ultimatemail.domain.inbox.LabelSummary
import com.qtekfun.ultimatemail.domain.inbox.MessageTimeFormatter
import com.qtekfun.ultimatemail.ui.theme.AvatarPalette
import com.qtekfun.ultimatemail.ui.theme.StarColor

private val IconSize = 16.dp

/**
 * One row of a conversation list (RF-03): avatar, sender, time, subject, snippet, indicators,
 * label chips and, when [accountMarker] is given, the account the conversation belongs to.
 *
 * Screen readers get one merged sentence ("Unread, from Ana, Lunch, ...") instead of the parts,
 * and the row is at least 72dp tall, so it is a comfortable touch target at any font scale.
 * Later screens (search, moving, selection) reuse it as is.
 *
 * @param item what to show, from `InboxListing`.
 * @param formatter formats the time; get one with [rememberMessageTimeFormatter].
 * @param onClick the row was tapped.
 * @param onLongClick the row was long-pressed (multi-selection); null leaves it out.
 * @param accountMarker pass it in the unified inbox only.
 * @param hiddenLabels labels not to show as chips, typically the folder being shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    item: ConversationItem,
    formatter: MessageTimeFormatter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    accountMarker: AccountMarker? = null,
    hiddenLabels: Set<String> = emptySet()
) {
    val labels = remember(item.labels, hiddenLabels) {
        LabelPresentation.summarize(item.labels, hiddenLabels)
    }
    val texts = rememberDescriptionTexts()
    val description = remember(item, formatter, labels, accountMarker, texts) {
        ConversationDescriber(texts)
            .describe(item, formatter.formatSpoken(item.sentAt), labels, accountMarker)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinRowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .rowSemantics(description, onClick, onLongClick)
            .padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        UnreadDot(item.unread)
        Avatar(
            name = item.senderName,
            address = item.senderAddress,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SenderLine(item, formatter.format(item.sentAt))
            SubjectLine(item)
            if (item.snippet.isNotBlank()) {
                Text(
                    text = item.snippet.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!labels.isEmpty || accountMarker != null) MetaLine(labels, accountMarker)
        }
    }
}

/** The account marker (unified inbox) and the label chips. */
@Composable
private fun MetaLine(labels: LabelSummary, accountMarker: AccountMarker?) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (accountMarker != null) AccountMarkerTag(accountMarker)
        if (!labels.isEmpty) LabelChipRow(labels, Modifier.weight(1f))
    }
}

/**
 * One merged description for screen readers, plus the click actions: clearing the semantics of
 * the parts would otherwise drop the actions of the clickable row.
 */
private fun Modifier.rowSemantics(
    description: String,
    onTap: () -> Unit,
    onHold: (() -> Unit)?
): Modifier = clearAndSetSemantics {
    contentDescription = description
    onClick {
        onTap()
        true
    }
    if (onHold != null) {
        onLongClick {
            onHold()
            true
        }
    }
}

/** Sender (bold when unread), the number of messages of the conversation, and the time. */
@Composable
private fun SenderLine(item: ConversationItem, time: String) {
    val weight = if (item.unread) FontWeight.Bold else FontWeight.Normal
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.sender,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = weight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.messageCount > 1) {
                Text(
                    text = item.messageCount.toString(),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = time,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = weight),
            color = if (item.unread) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1
        )
    }
}

@Composable
private fun SubjectLine(item: ConversationItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = item.subject.ifBlank { stringResource(R.string.conversation_no_subject) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (item.unread) FontWeight.SemiBold else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Indicators(item)
    }
}

private val MinRowHeight = 72.dp

@Composable
private fun UnreadDot(unread: Boolean) {
    Box(
        modifier = Modifier.width(10.dp).padding(top = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (unread) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
    Box(modifier = Modifier.width(6.dp))
}

/** The flag, attachment and pending-sync icons; they are described by the row, not alone. */
@Composable
private fun Indicators(item: ConversationItem) {
    Row(
        modifier = Modifier.padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.pendingSync) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        if (item.hasAttachments) {
            Icon(
                MailIcons.Attachment,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.flagged) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = StarColor
            )
        }
    }
}

@Composable
private fun AccountMarkerTag(marker: AccountMarker) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    AvatarPalette[marker.colorIndex.coerceIn(AvatarPalette.indices)],
                    CircleShape
                )
        )
        Text(
            text = marker.name,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The description phrases from string resources, rebuilt when the configuration changes. */
@Composable
fun rememberDescriptionTexts(): DescriptionTexts {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { ResourceDescriptionTexts(resources) }
}

private class ResourceDescriptionTexts(private val resources: Resources) : DescriptionTexts {
    override val unread: String = resources.getString(R.string.conversation_desc_unread)
    override val noSubject: String = resources.getString(R.string.conversation_no_subject)
    override val hasAttachment: String = resources.getString(R.string.conversation_desc_attachment)
    override val flagged: String = resources.getString(R.string.conversation_desc_flagged)
    override val pendingSync: String = resources.getString(R.string.conversation_desc_pending)

    override fun from(sender: String): String =
        resources.getString(R.string.conversation_desc_from, sender)

    override fun messages(count: Int): String =
        resources.getQuantityString(R.plurals.conversation_desc_messages, count, count)

    override fun labels(names: String): String =
        resources.getString(R.string.conversation_desc_labels, names)

    override fun account(name: String): String =
        resources.getString(R.string.conversation_desc_account, name)
}
