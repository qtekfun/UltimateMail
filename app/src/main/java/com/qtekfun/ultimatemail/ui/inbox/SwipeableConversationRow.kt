// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.SwipeDecision
import com.qtekfun.ultimatemail.domain.inbox.SwipeDirection
import com.qtekfun.ultimatemail.ui.components.MailIcons
import com.qtekfun.ultimatemail.ui.theme.StarColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

private val SwipeIconSize = 24.dp
private val SwipeIconPadding = 24.dp
private const val THRESHOLD_FRACTION = 0.4f
private const val REACHED_SCALE = 1.25f
private const val SCALE_MILLIS = 120

/** How one swipe action looks while it is revealed: the colour behind the row and its icon. */
private data class SwipeLook(val container: Color, val content: Color, val icon: ImageVector)

@Composable
private fun lookOf(action: SwipeAction, item: ConversationItem, available: Boolean): SwipeLook {
    val colors = MaterialTheme.colorScheme
    if (!available) return SwipeLook(colors.surfaceVariant, colors.outline, iconOf(action, item))
    return when (action) {
        SwipeAction.ARCHIVE ->
            SwipeLook(colors.primaryContainer, colors.onPrimaryContainer, MailIcons.Archive)

        SwipeAction.DELETE ->
            SwipeLook(colors.errorContainer, colors.onErrorContainer, Icons.Filled.Delete)

        SwipeAction.MOVE ->
            SwipeLook(colors.tertiaryContainer, colors.onTertiaryContainer, MailIcons.Move)

        SwipeAction.TOGGLE_READ ->
            SwipeLook(colors.secondaryContainer, colors.onSecondaryContainer, iconOf(action, item))

        SwipeAction.TOGGLE_STAR ->
            SwipeLook(
                StarColor.copy(alpha = STAR_BACKGROUND_ALPHA),
                colors.onSurface,
                iconOf(action, item)
            )

        SwipeAction.NONE -> SwipeLook(Color.Transparent, Color.Transparent, MailIcons.Archive)
    }
}

private const val STAR_BACKGROUND_ALPHA = 0.35f

private fun iconOf(action: SwipeAction, item: ConversationItem): ImageVector = when (action) {
    SwipeAction.ARCHIVE -> MailIcons.Archive
    SwipeAction.DELETE -> Icons.Filled.Delete
    SwipeAction.MOVE -> MailIcons.Move
    SwipeAction.TOGGLE_READ -> if (item.unread) MailIcons.MarkRead else Icons.Filled.Email
    SwipeAction.TOGGLE_STAR -> if (item.flagged) MailIcons.StarOutline else Icons.Filled.Star
    SwipeAction.NONE -> MailIcons.Archive
}

/**
 * What a swipe row needs: the action of each direction and what it does here, how to run a swipe,
 * and the state around it. [onSwipe] runs the action and says whether the row leaves the list;
 * if not it springs back. [restoreToken] changing to a value above 0 brings back a row that was
 * dismissed but is still in the list (the action could not be applied). [enabled] is false while
 * selecting.
 */
data class SwipeRow(
    val rightAction: SwipeAction,
    val right: SwipeDecision,
    val leftAction: SwipeAction,
    val left: SwipeDecision,
    val onSwipe: (SwipeDirection) -> Boolean,
    val enabled: Boolean,
    val restoreToken: Int,
    val reduceMotion: Boolean
)

/**
 * A conversation row that can be swiped (RF-11): the configured action of each direction is
 * revealed behind the row with its colour and icon, and runs when the row is released past the
 * threshold, with a tick of haptic feedback when the threshold is crossed. A direction whose
 * action is [SwipeDecision.Inactive] does not move; one that is blocked shows a grey background
 * and springs back. Gestures are one way to do things, never the only one: the row also has
 * screen reader actions and the selection bar.
 */
@Composable
fun SwipeableConversationRow(
    item: ConversationItem,
    swipe: SwipeRow,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * THRESHOLD_FRACTION }
    )
    SwipeEffects(state, swipe)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = swipe.enabled && swipe.right.draggable,
        enableDismissFromEndToStart = swipe.enabled && swipe.left.draggable,
        backgroundContent = { SwipeBackground(state, item, swipe) },
        content = {
            // Opaque, so the revealed colour is only seen where the row has moved away.
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) { content() }
        }
    )
}

/** Runs the action when the row is let go, ticks at the threshold, and restores on request. */
@Composable
private fun SwipeEffects(state: SwipeToDismissBoxState, swipe: SwipeRow) {
    val onSwipe by rememberUpdatedState(swipe.onSwipe)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.collect { value ->
            val direction = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeDirection.RIGHT
                SwipeToDismissBoxValue.EndToStart -> SwipeDirection.LEFT
                SwipeToDismissBoxValue.Settled -> null
            }
            // The row stays unless the action takes it out of the list: spring it back.
            if (direction != null && !onSwipe(direction)) state.reset()
        }
    }
    LaunchedEffect(state) {
        // The tick when the threshold is crossed, in either direction of travel.
        snapshotFlow { state.targetValue }.distinctUntilChanged().drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    LaunchedEffect(swipe.restoreToken) {
        if (swipe.restoreToken > 0) state.reset()
    }
}

/** The colour and icon of the action being revealed. */
@Composable
private fun SwipeBackground(
    state: SwipeToDismissBoxState,
    item: ConversationItem,
    swipe: SwipeRow
) {
    val direction = state.dismissDirection
    if (direction == SwipeToDismissBoxValue.Settled) return
    val toRight = direction == SwipeToDismissBoxValue.StartToEnd
    val action = if (toRight) swipe.rightAction else swipe.leftAction
    val decision = if (toRight) swipe.right else swipe.left
    val look = lookOf(action, item, decision !is SwipeDecision.Blocked)
    val reached = state.targetValue != SwipeToDismissBoxValue.Settled
    val scale by animateFloatAsState(
        targetValue = if (reached && !swipe.reduceMotion) REACHED_SCALE else 1f,
        animationSpec = if (swipe.reduceMotion) snap() else tween(SCALE_MILLIS),
        label = "swipe icon"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(look.container)
            .padding(horizontal = SwipeIconPadding)
            // Decorative: screen readers use the row's own actions.
            .clearAndSetSemantics {},
        contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Icon(
            look.icon,
            contentDescription = null,
            tint = look.content,
            modifier = Modifier.size(SwipeIconSize).scale(scale)
        )
    }
}
