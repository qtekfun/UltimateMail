// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.SwipeActions

/** The side a row is swiped towards. */
enum class SwipeDirection {
    /** From the left edge towards the right. */
    RIGHT,

    /** From the right edge towards the left. */
    LEFT;

    /** The action configured for this direction. */
    fun action(actions: SwipeActions): SwipeAction =
        if (this == RIGHT) actions.right else actions.left
}

/** Why a swipe action cannot run on a conversation, when the user should be told. */
enum class SwipeBlock { NO_ARCHIVE_FOLDER, NO_TRASH_FOLDER }

/** What swiping a conversation does, once the configured action meets the folder it is in. */
sealed interface SwipeDecision {
    /** Do [change] to the conversation. */
    data class Apply(val change: RowChange) : SwipeDecision

    /** Ask where to move the conversation. */
    data object PickFolder : SwipeDecision

    /** The action makes sense here but the account lacks the folder: say so, spring back. */
    data class Blocked(val reason: SwipeBlock) : SwipeDecision

    /** Nothing to do: no action configured, or it is pointless in this folder. */
    data object Inactive : SwipeDecision

    /** Whether the row follows the finger: false means it does not move at all. */
    val draggable: Boolean get() = this !is Inactive
}

/** Turns a configured [SwipeAction] into what it does to one conversation (RF-11). */
object SwipePlanner {
    fun decide(action: SwipeAction, item: ConversationItem, targets: RowTargets): SwipeDecision =
        when (action) {
            SwipeAction.NONE -> SwipeDecision.Inactive

            SwipeAction.MOVE -> SwipeDecision.PickFolder

            SwipeAction.TOGGLE_READ -> SwipeDecision.Apply(
                if (item.unread) RowChange.MARK_READ else RowChange.MARK_UNREAD
            )

            SwipeAction.TOGGLE_STAR -> SwipeDecision.Apply(
                if (item.flagged) RowChange.UNSTAR else RowChange.STAR
            )

            SwipeAction.ARCHIVE -> targets.of(item)?.let {
                when {
                    it.canArchive -> SwipeDecision.Apply(RowChange.ARCHIVE)
                    it.archivePath == null -> SwipeDecision.Blocked(SwipeBlock.NO_ARCHIVE_FOLDER)
                    else -> SwipeDecision.Inactive
                }
            } ?: SwipeDecision.Inactive

            SwipeAction.DELETE -> targets.of(item)?.let {
                when {
                    it.canDelete -> SwipeDecision.Apply(RowChange.DELETE)
                    it.trashPath == null -> SwipeDecision.Blocked(SwipeBlock.NO_TRASH_FOLDER)
                    else -> SwipeDecision.Inactive
                }
            } ?: SwipeDecision.Inactive
        }
}
