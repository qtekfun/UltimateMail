// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.LabelChipModel
import com.qtekfun.ultimatemail.domain.inbox.LabelSummary
import com.qtekfun.ultimatemail.ui.theme.ChipColors
import com.qtekfun.ultimatemail.ui.theme.LabelPaletteDark
import com.qtekfun.ultimatemail.ui.theme.LabelPaletteLight
import com.qtekfun.ultimatemail.ui.theme.isLight

/**
 * A Gmail-style coloured label chip. Build [chip] with `LabelPresentation.summarize`; the colour
 * follows the label, so it is the same in every list. It is not interactive, and screen readers
 * get the label through the description of the row or screen that shows it.
 */
@Composable
fun LabelChip(chip: LabelChipModel, modifier: Modifier = Modifier) {
    LabelChipText(chip.text, labelChipColors(chip.colorIndex), modifier)
}

/** The "+N" chip that stands for labels that did not fit. */
@Composable
fun LabelOverflowChip(count: Int, modifier: Modifier = Modifier) {
    LabelChipText(
        text = stringResource(R.string.label_overflow, count),
        colors = ChipColors(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier
    )
}

/** The chips of a [LabelSummary] side by side, wrapping onto more lines when the font is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelChipRow(summary: LabelSummary, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        summary.chips.forEach { LabelChip(it) }
        if (summary.overflow > 0) LabelOverflowChip(summary.overflow)
    }
}

@Composable
private fun labelChipColors(index: Int): ChipColors {
    // The theme of the app, which the user can set apart from the system's.
    val palette = if (MaterialTheme.colorScheme.surface.isLight()) {
        LabelPaletteLight
    } else {
        LabelPaletteDark
    }
    return palette[index.coerceIn(palette.indices)]
}

@Composable
private fun LabelChipText(text: String, colors: ChipColors, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(colors.container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = colors.content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
