// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R

private val MinTouchTarget = 48.dp

/**
 * The top bar while conversations are being selected (T16, restyled like iOS Mail): "Select
 * all" on the left, "Done" on the right and, between them, the count, which a screen reader
 * hears again whenever it changes. The actions are in the floating bar at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, actions: SelectionActions, modifier: Modifier = Modifier) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Text(
                pluralStringResource(R.plurals.inbox_selection_count, count, count),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        },
        navigationIcon = {
            BarTextButton(stringResource(R.string.inbox_select_all), actions.onSelectAll)
        },
        actions = { BarTextButton(stringResource(R.string.inbox_done), actions.onClear) }
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
