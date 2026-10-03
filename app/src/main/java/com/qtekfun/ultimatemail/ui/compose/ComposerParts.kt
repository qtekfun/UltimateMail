// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.compose.AttachmentLimits
import com.qtekfun.ultimatemail.domain.compose.RecipientChip
import com.qtekfun.ultimatemail.domain.conversation.FileSizeFormatter
import com.qtekfun.ultimatemail.ui.components.MailIcons

private val MinTouchTarget = 48.dp
private val LabelWidth = 56.dp
private val InputMinWidth = 120.dp

@Composable
internal fun formatSize(bytes: Long): String =
    FileSizeFormatter.format(bytes, LocalConfiguration.current.locales[0])

/** A notice inside the composer: an error to fix or a warning, dismissable, announced once. */
@Composable
internal fun Banner(text: String, error: Boolean, onDismiss: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = MinTouchTarget)
                ) {
                    Text(stringResource(R.string.composer_dismiss))
                }
            }
        }
    }
}

/**
 * One recipient line: the label, the chips and the text being typed (comma, semicolon, Next or
 * leaving the field turn it into a chip), and for To the button that reveals Cc and Bcc. The
 * suggestions for this field are listed right below it.
 */
@Composable
internal fun RecipientRow(
    kind: RecipientKind,
    state: ComposerState,
    actions: ComposerActions,
    trailing: (() -> Unit)? = null
) {
    val field = state.field(kind)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            stringResource(kind.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = LabelWidth)
                .heightIn(min = MinTouchTarget)
                .wrapContentHeight()
        )
        RecipientChips(
            chips = field.chips,
            input = field.input,
            kind = kind,
            actions = actions,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            TextButton(onClick = trailing, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.composer_show_cc_bcc))
            }
        }
    }
    state.suggestions?.takeIf { it.field == kind }?.items?.forEach { address ->
        val shown = address.name?.takeIf { it.isNotBlank() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable { actions.onPickSuggestion(kind, address) }
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (shown != null) Text(shown, style = MaterialTheme.typography.bodyLarge)
            Text(
                address.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun RecipientKind.labelRes() = when (this) {
    RecipientKind.TO -> R.string.composer_to
    RecipientKind.CC -> R.string.composer_cc
    RecipientKind.BCC -> R.string.composer_bcc
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientChips(
    chips: List<RecipientChip>,
    input: String,
    kind: RecipientKind,
    actions: ComposerActions,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    val label = stringResource(kind.labelRes())
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        chips.forEachIndexed { index, chip ->
            RecipientChipView(chip) { actions.onRemoveChip(kind, index) }
        }
        BasicTextField(
            value = input,
            onValueChange = { actions.onInput(kind, it) },
            modifier = Modifier
                .defaultMinSize(minWidth = InputMinWidth)
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = label }
                .onFocusChanged { if (!it.isFocused) actions.onCommit(kind) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    actions.onCommit(kind)
                    focus.moveFocus(FocusDirection.Next)
                }
            ),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) { inner() }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipientChipView(chip: RecipientChip, onRemove: () -> Unit) {
    val remove = stringResource(R.string.composer_remove_recipient, chip.text)
    val description = if (chip.valid) {
        remove
    } else {
        stringResource(R.string.composer_invalid_recipient, chip.text) + ". " + remove
    }
    val colors = MaterialTheme.colorScheme
    val shown = chip.address?.let { it.name?.takeIf(String::isNotBlank) ?: it.address } ?: chip.text
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        colors = if (chip.valid) {
            InputChipDefaults.inputChipColors()
        } else {
            InputChipDefaults.inputChipColors(
                containerColor = colors.errorContainer,
                labelColor = colors.onErrorContainer,
                trailingIconColor = colors.onErrorContainer
            )
        },
        border = if (chip.valid) {
            InputChipDefaults.inputChipBorder(enabled = true, selected = false)
        } else {
            BorderStroke(1.dp, colors.error)
        },
        modifier = Modifier.semantics { contentDescription = description }
    )
}

/** The files attached, each with its size and a button to remove it, and the size warning. */
@Composable
internal fun AttachmentsSection(state: ComposerState, onRemove: (Long) -> Unit) {
    if (state.attachments.isEmpty()) return
    if (state.attachmentWarning) {
        Banner(
            stringResource(
                R.string.composer_attachment_warning,
                formatSize(state.attachmentBytes),
                formatSize(AttachmentLimits.WARN_BYTES)
            ),
            error = false
        )
    }
    Text(
        stringResource(R.string.composer_attachments),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
            .semantics { heading() }
    )
    state.attachments.forEach { attachment ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(MailIcons.Attachment, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    attachment.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatSize(attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { onRemove(attachment.id) },
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.composer_remove_attachment,
                        attachment.displayName
                    )
                )
            }
        }
    }
}
