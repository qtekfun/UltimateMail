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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.domain.conversation.AttachmentKind
import com.qtekfun.ultimatemail.domain.conversation.AttachmentView
import com.qtekfun.ultimatemail.domain.conversation.FileSizeFormatter
import com.qtekfun.ultimatemail.ui.components.MailIcons

private val MinTouchTarget = 48.dp
private val IconSize = 24.dp

/** The attachments of a message, each with its state; nothing is downloaded until it is used. */
@Composable
fun AttachmentList(
    attachments: List<AttachmentView>,
    onAction: (id: Long, action: AttachmentAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = pluralStringResource(
                R.plurals.attachments_title,
                attachments.size,
                attachments.size
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        attachments.forEach { AttachmentRow(it, onAction) }
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentView,
    onAction: (id: Long, action: AttachmentAction) -> Unit
) {
    val name = attachment.name.ifBlank { stringResource(R.string.attachment_unnamed) }
    val busy = attachment.state == AttachmentState.DOWNLOADING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AttachmentInfo(
            attachment = attachment,
            name = name,
            busy = busy,
            onOpen = { onAction(attachment.id, AttachmentAction.OPEN) },
            modifier = Modifier.weight(1f)
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(12.dp)
                    .size(IconSize)
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp
            )
        } else {
            IconButton(
                onClick = { onAction(attachment.id, AttachmentAction.SHARE) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.attachment_share_named, name)
                )
            }
            IconButton(
                onClick = { onAction(attachment.id, AttachmentAction.SAVE) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(
                    MailIcons.Download,
                    contentDescription = stringResource(R.string.attachment_save_named, name)
                )
            }
        }
    }
}

/** The icon, name, size and state of an attachment; tapping it opens the file. */
@Composable
private fun AttachmentInfo(
    attachment: AttachmentView,
    name: String,
    busy: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val size = FileSizeFormatter.format(attachment.size, locale)
    val state = stringResource(attachment.state.label())
    val kind = stringResource(attachment.kind.label())
    val description = stringResource(R.string.attachment_description, kind, name, size, state)
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(
                enabled = !busy,
                onClickLabel = stringResource(R.string.attachment_open_named, name),
                onClick = onOpen
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = attachment.kind.icon(),
            contentDescription = null,
            modifier = Modifier.size(IconSize),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$size · $state",
                style = MaterialTheme.typography.bodySmall,
                color = if (attachment.state == AttachmentState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@StringRes
private fun AttachmentState.label(): Int = when (this) {
    AttachmentState.REMOTE -> R.string.attachment_state_remote
    AttachmentState.DOWNLOADING -> R.string.attachment_state_downloading
    AttachmentState.DOWNLOADED -> R.string.attachment_state_downloaded
    AttachmentState.FAILED -> R.string.attachment_state_failed
}

@StringRes
private fun AttachmentKind.label(): Int = when (this) {
    AttachmentKind.IMAGE -> R.string.attachment_kind_image
    AttachmentKind.PDF -> R.string.attachment_kind_pdf
    AttachmentKind.DOCUMENT -> R.string.attachment_kind_document
    AttachmentKind.SPREADSHEET -> R.string.attachment_kind_spreadsheet
    AttachmentKind.PRESENTATION -> R.string.attachment_kind_presentation
    AttachmentKind.TEXT -> R.string.attachment_kind_text
    AttachmentKind.ARCHIVE -> R.string.attachment_kind_archive
    AttachmentKind.AUDIO -> R.string.attachment_kind_audio
    AttachmentKind.VIDEO -> R.string.attachment_kind_video
    AttachmentKind.OTHER -> R.string.attachment_kind_other
}

private fun AttachmentKind.icon(): ImageVector = when (this) {
    AttachmentKind.IMAGE -> MailIcons.Image

    AttachmentKind.PDF, AttachmentKind.DOCUMENT, AttachmentKind.SPREADSHEET,
    AttachmentKind.PRESENTATION, AttachmentKind.TEXT -> MailIcons.Document

    AttachmentKind.ARCHIVE -> MailIcons.Archive

    AttachmentKind.AUDIO, AttachmentKind.VIDEO, AttachmentKind.OTHER -> MailIcons.File
}
