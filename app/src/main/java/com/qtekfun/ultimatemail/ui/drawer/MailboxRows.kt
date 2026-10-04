// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.ui.theme.LocalDensityMetrics

private val CardCorner = 12.dp
private val IndentPerLevel = 16.dp
private val RowIconSize = 24.dp
private val RowStart = 16.dp
private val IconToText = 12.dp

/** Where a row sits in its rounded card: it decides the corners and whether a divider follows. */
internal enum class CardPosition {
    Single,
    Top,
    Middle,
    Bottom;

    val hasDividerBelow: Boolean get() = this == Top || this == Middle

    val shape: Shape
        get() = when (this) {
            Single -> RoundedCornerShape(CardCorner)
            Top -> RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner)
            Middle -> RoundedCornerShape(0.dp)
            Bottom -> RoundedCornerShape(bottomStart = CardCorner, bottomEnd = CardCorner)
        }

    companion object {
        /** The position of the row at [index] in a card of [size] rows. */
        fun of(index: Int, size: Int): CardPosition = when {
            size <= 1 -> Single
            index == 0 -> Top
            index == size - 1 -> Bottom
            else -> Middle
        }
    }
}

/** The fill of a card: a step away from the sheet behind it, in light and in dark themes. */
@Composable
internal fun cardColor(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.surface.luminance() < DARK_LUMINANCE) {
        colors.surfaceContainerHighest
    } else {
        colors.surfaceContainerLowest
    }
}

/** The sheet behind the cards. */
@Composable
internal fun sheetColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** Small secondary text above a card, like the section titles of iOS lists. */
@Composable
internal fun CardHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * One row of a card: an accent-colored icon, the label, the count at the right in secondary
 * color and, for a parent folder, its expand/collapse button. The height follows the display
 * density setting.
 */
@Composable
internal fun MailboxRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    position: CardPosition,
    modifier: Modifier = Modifier,
    indent: Int = 0,
    count: Int = 0,
    /** Null when the row has no children; otherwise whether they are showing. */
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    /** What the toggle button says for a screen reader; defaults to expand/collapse [label]. */
    toggleDescription: String? = null,
    /** What a screen reader says for the count when it is not an unread count. */
    countDescription: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val text = if (selected) colors.onSecondaryContainer else colors.onSurface
    val divider = colors.outlineVariant
    val dividerStart = with(LocalDensity.current) {
        (RowStart + RowIconSize + IconToText).toPx()
    }
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = LocalDensityMetrics.current.drawerRowHeight)
            .clip(position.shape)
            .background(if (selected) colors.secondaryContainer else cardColor())
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (position.hasDividerBelow && !selected) {
                    drawLine(
                        divider,
                        Offset(dividerStart, size.height),
                        Offset(size.width, size.height),
                        strokeWidth = 1f
                    )
                }
            }
            .padding(start = RowStart + IndentPerLevel * indent, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(RowIconSize)
        )
        Spacer(Modifier.width(IconToText))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RowTrailing(
            count = count,
            countDescription = countDescription,
            selected = selected,
            toggle = expanded?.let { ToggleState(label, it, onToggle, text, toggleDescription) }
        )
    }
}

private class ToggleState(
    val label: String,
    val expanded: Boolean,
    val onToggle: () -> Unit,
    val tint: Color,
    val description: String?
)

@Composable
private fun RowTrailing(
    count: Int,
    countDescription: String?,
    selected: Boolean,
    toggle: ToggleState?
) {
    val colors = MaterialTheme.colorScheme
    if (count > 0) {
        val description = countDescription
            ?: pluralStringResource(R.plurals.drawer_unread_count, count, count)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = description }
        )
    }
    if (toggle != null) {
        ToggleButton(
            toggle.label,
            toggle.expanded,
            toggle.onToggle,
            toggle.tint,
            toggle.description
        )
    } else {
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ToggleButton(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    tint: Color,
    description: String?
) {
    val size: Dp = LocalDensityMetrics.current.drawerRowHeight
    val said = description ?: stringResource(
        if (expanded) R.string.drawer_collapse else R.string.drawer_expand,
        label
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = said },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = tint
        )
    }
}

private const val DARK_LUMINANCE = 0.5f
