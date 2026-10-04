// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.picker.CheckState
import com.qtekfun.ultimatemail.domain.picker.PickerFolder
import com.qtekfun.ultimatemail.domain.picker.PickerListItem
import com.qtekfun.ultimatemail.domain.picker.shownPath
import com.qtekfun.ultimatemail.ui.drawer.icon

private val MinTouchTarget = 48.dp
private val IndentPerLevel = 16.dp
private val SideMargin = 16.dp
private const val DISABLED_ALPHA = 0.38f
private const val MAX_LINES = 2

/** A parent folder that cannot be chosen: it only explains the nesting below it. */
@Composable
internal fun GroupRow(group: PickerListItem.Group, indented: Boolean) {
    Text(
        text = group.name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SideMargin + if (indented) IndentPerLevel * group.depth else 0.dp,
                end = SideMargin,
                top = 12.dp,
                bottom = 4.dp
            )
    )
}

/**
 * A destination. One accessibility node: name (with its path while searching), state and role
 * are announced together, and the row is at least 48dp high however large the font is.
 */
@Composable
internal fun EntryRow(
    entry: PickerListItem.Entry,
    labels: Boolean,
    flat: Boolean,
    onClick: (PickerFolder) -> Unit
) {
    val folder = entry.folder
    val path = if (entry.showPath) folder.shownPath() else null
    val indent = if (flat) 0.dp else IndentPerLevel * folder.depth
    Row(
        modifier = Modifier.entryRow(entry, path, labels && !folder.moveTarget, onClick).padding(
            start = SideMargin + indent,
            end = SideMargin,
            top = 4.dp,
            bottom = 4.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The row speaks for itself; its parts stay silent so nothing is read twice.
        Row(
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryContent(entry, path, labels)
        }
    }
}

/** Size, click behaviour and the one accessibility description of an entry row. */
@Composable
private fun Modifier.entryRow(
    entry: PickerListItem.Entry,
    path: String?,
    labels: Boolean,
    onClick: (PickerFolder) -> Unit
): Modifier {
    val folder = entry.folder
    val description = description(folder, path)
    val stateText = stateText(entry.state)
    val base = this
        .fillMaxWidth()
        .heightIn(min = MinTouchTarget)
        .alpha(if (folder.enabled) 1f else DISABLED_ALPHA)
    return when {
        !folder.enabled -> base.semantics(mergeDescendants = true) {
            contentDescription = description
            disabled()
        }

        labels -> base.triStateToggleable(
            state = entry.state.toToggleable(),
            onClick = { onClick(folder) },
            role = Role.Checkbox
        ).semantics {
            contentDescription = description
            stateDescription = stateText
        }

        else -> base.clickable(role = Role.Button, onClick = { onClick(folder) })
            .semantics { contentDescription = description }
    }
}

@Composable
private fun RowScope.EntryContent(entry: PickerListItem.Entry, path: String?, labels: Boolean) {
    val folder = entry.folder
    if (labels && folder.moveTarget) {
        // Trash and Spam move the messages instead of labelling them: no box, but the icons
        // stay lined up with the rows that have one.
        Spacer(Modifier.width(CheckboxWidth))
    } else if (labels) {
        TriStateCheckbox(state = entry.state.toToggleable(), onClick = null)
    }
    Icon(
        imageVector = folder.role.icon(folder.isLabel),
        contentDescription = null,
        tint = iconTint(folder.role),
        modifier = Modifier.padding(start = if (labels) 12.dp else 0.dp, end = 16.dp).size(24.dp)
    )
    Column(Modifier.weight(1f)) {
        Text(
            text = highlighted(folder.name, entry.nameHighlight),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
        if (path != null) {
            Text(
                text = highlighted(path, entry.pathHighlight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = MAX_LINES,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val CheckboxWidth = 48.dp

/** Trash and Spam stay selectable but look different: moving there is a deliberate act. */
@Composable
private fun iconTint(role: FolderRole): Color = when (role) {
    FolderRole.TRASH, FolderRole.JUNK -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun description(folder: PickerFolder, path: String?): String {
    val named = if (path == null) {
        folder.name
    } else {
        stringResource(R.string.picker_desc_path, folder.name, path)
    }
    return if (folder.enabled) named else stringResource(R.string.picker_desc_current, named)
}

@Composable
private fun stateText(state: CheckState): String = stringResource(
    when (state) {
        CheckState.CHECKED -> R.string.picker_state_checked
        CheckState.PARTIAL -> R.string.picker_state_partial
        CheckState.UNCHECKED -> R.string.picker_state_unchecked
    }
)

private fun CheckState.toToggleable() = when (this) {
    CheckState.CHECKED -> ToggleableState.On
    CheckState.PARTIAL -> ToggleableState.Indeterminate
    CheckState.UNCHECKED -> ToggleableState.Off
}

/** [text] with the inclusive [ranges] shown bold on a tinted background. */
@Composable
private fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    val style = SpanStyle(
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer
    )
    return buildAnnotatedString {
        var from = 0
        ranges.forEach { range ->
            val start = range.first.coerceIn(from, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            append(text.substring(from, start))
            withStyle(style) { append(text.substring(start, end)) }
            from = end
        }
        append(text.substring(from))
    }
}
