// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.qtekfun.ultimatemail.domain.search.HighlightField
import com.qtekfun.ultimatemail.domain.search.SearchHighlights
import com.qtekfun.ultimatemail.ui.theme.AvatarPalette
import com.qtekfun.ultimatemail.ui.theme.LocalDensityMetrics
import com.qtekfun.ultimatemail.ui.theme.RowLeadingSlot
import com.qtekfun.ultimatemail.ui.theme.starColor

private val IconSize = 16.dp
private val UnreadDotSize = 10.dp
private val SelectionCircleSize = 22.dp
private val CheckSize = 16.dp

/**
 * The fixed-width slot at the start of a row, so the text of every row lines up: the unread dot
 * in the accent color, or, while [selecting], the selection circle (checked when [selected]).
 * It is as tall as the first line of text, so the dot sits on the sender's line at any font size.
 */
@Composable
internal fun LeadingSlot(unread: Boolean, selecting: Boolean, selected: Boolean, dotColor: Color) {
    val firstLine = with(LocalDensity.current) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp()
    }
    Box(
        modifier = Modifier.width(RowLeadingSlot).height(firstLine),
        contentAlignment = Alignment.Center
    ) {
        if (selecting || selected) {
            SelectionCircle(selected)
        } else if (unread) {
            Box(modifier = Modifier.size(UnreadDotSize).background(dotColor, CircleShape))
        }
    }
}

/** An empty ring, or a filled circle with a check when [selected]. */
@Composable
private fun SelectionCircle(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val base = Modifier.size(SelectionCircleSize).clip(CircleShape)
    if (selected) {
        Box(
            modifier = base.background(primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(CheckSize)
            )
        }
    } else {
        Box(
            modifier = base.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/** The flag, attachment and pending-sync icons; they are described by the row, not alone. */
@Composable
internal fun Indicators(item: ConversationItem) {
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
                tint = starColor()
            )
        }
    }
}

@Composable
internal fun AccountMarkerTag(marker: AccountMarker) {
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
