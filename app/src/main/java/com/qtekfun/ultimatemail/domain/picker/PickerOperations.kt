// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.folder.FolderTree
import com.qtekfun.ultimatemail.sync.queue.NewOperation

/**
 * Turns the user's choice into the operations to queue (RF-06), and the operations that undo
 * them. Every function returns null when there is nothing to do, so no empty snackbar is shown.
 * Operations name each message by the folder and UID the user sees it under, as the queue expects.
 */
object PickerOperations {
    /** One MOVE per message to [target]; messages already in it are skipped. */
    fun move(request: PickerRequest, target: PickerFolder): PickerResult? =
        moveTo(request, target.path, PickerOutcome.Moved(target.path, target.name))

    /**
     * ADD_LABEL / REMOVE_LABEL per message and changed label. [messageLabels] are the labels each
     * message of [request] has now, in the same order (see [PickerCandidates.implicitLabel]);
     * a label a message already has is not added again, and one it lacks is not removed.
     */
    fun labels(
        request: PickerRequest,
        messageLabels: List<Set<String>>,
        changes: LabelChanges,
        candidates: PickerCandidates
    ): PickerResult? {
        fun nameOf(label: String) = candidates.byValue(label)?.name ?: label
        val outcome = PickerOutcome.LabelsChanged(
            added = changes.add.sorted().map(::nameOf),
            removed = changes.remove.sorted().map(::nameOf)
        )
        return changeLabels(request, messageLabels, changes, outcome)
    }

    /**
     * Archive: remove the Inbox label on Gmail ([PickerMode.LABELS]), move to the Archive folder
     * elsewhere. Null when there is nothing to archive, or no Archive folder is known.
     */
    fun archive(
        request: PickerRequest,
        mode: PickerMode,
        tree: FolderTree,
        messageLabels: List<Set<String>>
    ): PickerResult? = when (mode) {
        PickerMode.LABELS -> changeLabels(
            request,
            messageLabels,
            LabelChanges(add = emptySet(), remove = setOf(GmailLabels.INBOX)),
            PickerOutcome.Archived
        )

        PickerMode.FOLDERS -> tree.special.firstOrNull { it.role == FolderRole.ARCHIVE }
            ?.let { moveTo(request, it.path, PickerOutcome.Archived) }
    }

    private fun moveTo(
        request: PickerRequest,
        path: String,
        outcome: PickerOutcome
    ): PickerResult? {
        val moving = request.messages.filter { it.folderPath != path }
        if (moving.isEmpty()) return null
        return PickerResult(
            accountId = request.accountId,
            outcome = outcome,
            messageCount = moving.size,
            operations = moving.map { operation(request, OperationType.MOVE, it, path) },
            // Moving back to where it came from: the queue cancels the move while it is waiting.
            inverse = moving.map { operation(request, OperationType.MOVE, it, it.folderPath) }
        )
    }

    private fun changeLabels(
        request: PickerRequest,
        messageLabels: List<Set<String>>,
        changes: LabelChanges,
        outcome: PickerOutcome
    ): PickerResult? {
        require(messageLabels.size == request.messages.size) { "One label set per message" }
        val operations = ArrayList<NewOperation>()
        var touched = 0
        request.messages.forEachIndexed { index, message ->
            val has = messageLabels[index]
            val before = operations.size
            changes.add.sorted().filter { it !in has }.forEach {
                operations += operation(request, OperationType.ADD_LABEL, message, it)
            }
            changes.remove.sorted().filter { it in has }.forEach {
                operations += operation(request, OperationType.REMOVE_LABEL, message, it)
            }
            if (operations.size > before) touched++
        }
        if (operations.isEmpty()) return null
        return PickerResult(
            accountId = request.accountId,
            outcome = outcome,
            messageCount = touched,
            operations = operations,
            inverse = operations.asReversed().map { it.copy(type = it.type.opposite()) }
        )
    }

    private fun operation(
        request: PickerRequest,
        type: OperationType,
        message: MessageRef,
        payload: String
    ) = NewOperation(request.accountId, type, message.folderPath, message.uid, payload)

    /** Only ever called for the two label operations. */
    private fun OperationType.opposite() =
        if (this == OperationType.ADD_LABEL) OperationType.REMOVE_LABEL else OperationType.ADD_LABEL
}
