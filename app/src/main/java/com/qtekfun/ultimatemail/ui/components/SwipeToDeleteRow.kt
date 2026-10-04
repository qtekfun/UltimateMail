// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.ui.inbox.springBack

private const val THRESHOLD_FRACTION = 0.5f
private val IconPadding = 24.dp

/**
 * A row that is thrown away by swiping it either way (drafts, outbox). [onDelete] runs once the
 * row is let go past the threshold and must take the row out of the list (the lists hide it until
 * the Undo window ends). The same care as the conversation rows: the effect reads `settledValue`,
 * and a row that starts in the swiped state (its saved state came back with it after an Undo)
 * only returns to its place, it is not a new swipe. Swiping is never the only way: the rows keep
 * their own menu or button for the same action, which screen readers use.
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * THRESHOLD_FRACTION }
    )
    val delete by rememberUpdatedState(onDelete)
    LaunchedEffect(state) {
        var first = true
        snapshotFlow { state.settledValue }.collect { value ->
            val restored = first && value != SwipeToDismissBoxValue.Settled
            first = false
            when {
                restored -> state.springBack()
                value != SwipeToDismissBoxValue.Settled -> delete()
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = { DeleteBackground(state) },
        content = {
            // Opaque, so the colour behind is only seen where the row has moved away.
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) { content() }
        }
    )
}

@Composable
private fun DeleteBackground(state: SwipeToDismissBoxState) {
    val direction = state.dismissDirection
    if (direction == SwipeToDismissBoxValue.Settled) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = IconPadding)
            // Decorative: screen readers use the row's own delete action.
            .clearAndSetSemantics {},
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        }
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
