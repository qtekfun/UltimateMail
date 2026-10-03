// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/**
 * Rules 2 and 5 for move, label and delete operations: find the message again by identity when
 * its UID changed, notice when the work was already done, and discard (with a notice) when the
 * message is gone.
 */
object TargetResolver {
    /** [serverMessages] must cover the operation's folder and, for MOVE, the destination. */
    fun resolve(
        operation: TargetedOperation,
        serverMessages: List<ServerMessage>
    ): TargetResolution {
        val sameMessage = serverMessages.filter { it.identity.matches(operation.identity) }
        val source = locateSource(operation, serverMessages, sameMessage)
        return when {
            operation.kind == TargetKind.MOVE && source == null &&
                sameMessage.any { it.folderPath == operation.argument } ->
                TargetResolution.AlreadyApplied

            source == null -> vanished(operation)

            isDone(operation, source) -> TargetResolution.AlreadyApplied

            else -> TargetResolution.Apply(source.uid)
        }
    }

    /**
     * The message at the remembered UID, but only if it is still the same message; otherwise the
     * UID was reused or reassigned, so look it up by identity in the same folder.
     */
    private fun locateSource(
        operation: TargetedOperation,
        all: List<ServerMessage>,
        sameMessage: List<ServerMessage>
    ): ServerMessage? {
        val atUid = all.firstOrNull {
            it.folderPath == operation.folderPath && it.uid == operation.uid &&
                (operation.identity.isEmpty() || it.identity.matches(operation.identity))
        }
        return atUid ?: sameMessage.firstOrNull { it.folderPath == operation.folderPath }
    }

    private fun isDone(operation: TargetedOperation, source: ServerMessage) =
        when (operation.kind) {
            TargetKind.ADD_LABEL -> operation.argument in source.labels
            TargetKind.REMOVE_LABEL -> operation.argument !in source.labels
            TargetKind.MOVE, TargetKind.DELETE -> false
        }

    private fun vanished(operation: TargetedOperation) = if (operation.kind == TargetKind.DELETE) {
        // The user wanted it gone and it is: nothing to tell.
        TargetResolution.AlreadyApplied
    } else {
        TargetResolution.Discard(
            SyncNotice.MessageVanished(operation.accountId, operation.folderPath, operation.id)
        )
    }
}
