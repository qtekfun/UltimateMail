// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.sync.queue.NewOperation

/** What was done, for the message the caller shows after the picker closes. */
sealed interface PickerOutcome {
    /** The messages were moved to the folder at [path], which is shown as [name]. */
    data class Moved(val path: String, val name: String) : PickerOutcome

    /** Labels changed; [added] and [removed] are display names. */
    data class LabelsChanged(val added: List<String>, val removed: List<String>) : PickerOutcome

    /** The Inbox label was removed (Gmail), or the messages moved to the Archive folder. */
    data object Archived : PickerOutcome
}

/**
 * The result of the picker, handed to the caller. [operations] were queued; [inverse] undoes
 * them, in the order to queue them, through `MoveLabelActions.undo`. A move is undone by queueing
 * the move back, which the operation queue folds into the original while that is still waiting;
 * after the server has it, the undo is a move of a message that is no longer there, which the
 * sync engine reports as a vanished message. So offer undo for a few seconds, as a snackbar does.
 */
data class PickerResult(
    val accountId: Long,
    val outcome: PickerOutcome,
    val messageCount: Int,
    val operations: List<NewOperation>,
    val inverse: List<NewOperation>
)
