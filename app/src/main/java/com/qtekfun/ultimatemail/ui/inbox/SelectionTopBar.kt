// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R

private val MinTouchTarget = 48.dp
private val BarHeight = 64.dp

/** From this font scale the count goes under the buttons: three things do not fit in one row. */
private const val STACK_FONT_SCALE = 1.3f

/**
 * The top bar while conversations are being selected (T16, restyled like iOS Mail): "Select
 * all" on the left, "Done" on the right and the count, which a screen reader hears again
 * whenever it changes, between them (under them with a large font). The actions are in the
 * floating bar at the bottom.
 */
@Composable
fun SelectionTopBar(count: Int, actions: SelectionActions, modifier: Modifier = Modifier) {
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = BarHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarTextButton(stringResource(R.string.inbox_select_all), actions.onSelectAll)
                if (stacked) {
                    Spacer(Modifier.weight(1f))
                } else {
                    SelectionCount(count, Modifier.weight(1f))
                }
                BarTextButton(stringResource(R.string.inbox_done), actions.onClear)
            }
            if (stacked) SelectionCount(count, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun SelectionCount(count: Int, modifier: Modifier) {
    Text(
        pluralStringResource(R.plurals.inbox_selection_count, count, count),
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun BarTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
    ) {
        Text(text, maxLines = 1)
    }
}
