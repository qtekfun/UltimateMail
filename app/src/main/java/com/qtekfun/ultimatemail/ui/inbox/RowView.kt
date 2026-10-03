// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.MessageTimeFormatter
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.domain.inbox.SwipeDecision
import com.qtekfun.ultimatemail.domain.inbox.SwipeDirection
import com.qtekfun.ultimatemail.domain.inbox.SwipePlanner
import com.qtekfun.ultimatemail.ui.components.ConversationRow

/** How a row is drawn, apart from what it shows. */
internal class RowView(
    val formatter: MessageTimeFormatter,
    val hiddenLabels: Set<String>,
    val restoreToken: Int,
    val reduceMotion: Boolean
)

@Composable
internal fun LazyItemScope.InboxRow(
    item: ConversationItem,
    state: InboxState,
    actions: InboxActions,
    view: RowView
) {
    val selecting = state.selection.active
    val picked = item.key in state.selection
    val swipe = SwipeRow(
        rightAction = state.swipe.right,
        right = SwipePlanner.decide(state.swipe.right, item, state.targets),
        leftAction = state.swipe.left,
        left = SwipePlanner.decide(state.swipe.left, item, state.targets),
        onSwipe = { direction -> actions.selection.onSwipe(item, direction) },
        enabled = !selecting,
        restoreToken = view.restoreToken,
        reduceMotion = view.reduceMotion
    )
    SwipeableConversationRow(
        item = item,
        swipe = swipe,
        modifier = if (view.reduceMotion) Modifier else Modifier.animateItem()
    ) {
        ConversationRow(
            item = item,
            formatter = view.formatter,
            onClick = {
                if (selecting) {
                    actions.selection.onToggle(
                        item
                    )
                } else {
                    actions.onOpenConversation(item)
                }
            },
            onLongClick = { actions.selection.onToggle(item) },
            selected = picked,
            customActions = rowActions(item, swipe, selecting, picked, actions.selection),
            accountMarker = state.markers[item.accountId],
            hiddenLabels = view.hiddenLabels
        )
    }
}

/**
 * What a screen reader offers on a row in place of the gestures: picking the row, and the swipe
 * actions that apply (T16). While selecting, only picking: the bar has the rest.
 */
@Composable
private fun rowActions(
    item: ConversationItem,
    swipe: SwipeRow,
    selecting: Boolean,
    picked: Boolean,
    selection: SelectionActions
): List<CustomAccessibilityAction> {
    val select = stringResource(if (picked) R.string.inbox_deselect else R.string.inbox_select)
    val swipes = if (selecting) {
        emptyList()
    } else {
        listOf(SwipeDirection.RIGHT to swipe.right, SwipeDirection.LEFT to swipe.left)
            .mapNotNull { (direction, decision) -> decision.labelRes()?.let { direction to it } }
            .distinctBy { it.second }
            .map { (direction, label) -> direction to stringResource(label) }
    }
    return buildList {
        add(
            CustomAccessibilityAction(select) {
                selection.onToggle(item)
                true
            }
        )
        swipes.forEach { (direction, label) ->
            add(
                CustomAccessibilityAction(label) {
                    selection.onSwipe(item, direction)
                    true
                }
            )
        }
    }
}

@StringRes
private fun SwipeDecision.labelRes(): Int? = when (this) {
    is SwipeDecision.Apply -> when (change) {
        RowChange.ARCHIVE -> R.string.conversation_archive
        RowChange.DELETE -> R.string.conversation_delete
        RowChange.MARK_READ -> R.string.inbox_action_mark_read
        RowChange.MARK_UNREAD -> R.string.conversation_mark_unread
        RowChange.STAR -> R.string.conversation_star
        RowChange.UNSTAR -> R.string.conversation_unstar
    }

    SwipeDecision.PickFolder -> R.string.inbox_action_move

    is SwipeDecision.Blocked, SwipeDecision.Inactive -> null
}
