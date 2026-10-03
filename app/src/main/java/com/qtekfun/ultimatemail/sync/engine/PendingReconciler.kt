// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.sync.conflict.FlagPatch
import com.qtekfun.ultimatemail.sync.conflict.FlagResolution
import com.qtekfun.ultimatemail.sync.conflict.FlagResolver
import com.qtekfun.ultimatemail.sync.conflict.Flags
import com.qtekfun.ultimatemail.sync.conflict.FolderResetPlanner
import com.qtekfun.ultimatemail.sync.conflict.FolderSnapshot
import com.qtekfun.ultimatemail.sync.conflict.LocalMessage
import com.qtekfun.ultimatemail.sync.conflict.MessageIdentity
import com.qtekfun.ultimatemail.sync.conflict.PendingFlagOperation
import com.qtekfun.ultimatemail.sync.conflict.PendingTarget
import com.qtekfun.ultimatemail.sync.conflict.ServerMessage
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.conflict.TargetKind
import com.qtekfun.ultimatemail.sync.conflict.TargetResolution
import com.qtekfun.ultimatemail.sync.conflict.TargetResolver
import com.qtekfun.ultimatemail.sync.conflict.TargetedOperation
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import javax.inject.Inject

/**
 * Maps Room and the queue onto the conflict rules of SPEC section 5 (package sync.conflict) for a
 * pull:
 *
 * - UIDVALIDITY changed: [FolderResetPlanner] says what to drop, keep and remap. Messages that
 *   queued operations wait on are kept under a negative uid (no server uses one), together with
 *   their operations, so the work survives even if the run dies half way.
 * - Once the folder is downloaded again, [TargetResolver] finds each message again by Message-ID
 *   or X-GM-MSGID and the operation gets its new UID, or is completed or discarded with a notice.
 * - [FlagResolver] lays the pending flag changes over the flags the server reported.
 * - The "pending sync" indicator of every message of the folder is set from the queue.
 */
internal class PendingReconciler @Inject constructor(
    private val messages: MessageDao,
    private val operations: PendingOperationDao,
    private val notices: SyncNotices
) {
    /**
     * Invalidates [folder] if [serverUidValidity] differs from the stored one. Returns whether it
     * did. Safe to repeat after an interruption.
     */
    suspend fun resetIfInvalidated(folder: FolderEntity, serverUidValidity: Long): Boolean {
        val accountId = folder.accountId
        val rows = messages.identities(accountId, folder.path)
        val waiting = operations.all(accountId)
            .filter { it.folderPath == folder.path && it.uid > 0 && it.type.isServerOperation() }
        val snapshot = FolderSnapshot(
            accountId = accountId,
            folderPath = folder.path,
            storedUidValidity = folder.uidValidity,
            // Rows without a server uid (0 or below) were never on the server: local drafts.
            messages = rows.map {
                LocalMessage(
                    it.id,
                    it.uid,
                    MessageIdentity(it.messageId, it.gmailMessageId),
                    it.uid <= 0
                )
            },
            pending = waiting.map { PendingTarget(it.id, it.uid) }
        )
        val plan = FolderResetPlanner.plan(snapshot, serverUidValidity) ?: return false
        plan.discardOperationIds.forEach { operations.delete(it) }
        operations.detachFromServerUids(accountId, folder.path)
        messages.parkReferencedByOperations(accountId, folder.path)
        plan.dropRowIds.chunked(DELETE_CHUNK).forEach { messages.deleteServerRows(it) }
        plan.notices.forEach(notices::publish)
        return true
    }

    /** The queued flag changes of the messages of a folder, by uid. */
    suspend fun flagOperations(
        accountId: Long,
        folderPath: String
    ): Map<Long, List<PendingFlagOperation>> = operations.all(accountId)
        .filter { it.folderPath == folderPath && it.uid > 0 }
        .groupBy { it.uid }
        .mapValues { (_, queued) -> queued.mapNotNull { it.toFlagOperation() } }
        .filterValues { it.isNotEmpty() }

    /** Operations the server already reflects need no sending. */
    suspend fun complete(resolution: FlagResolution) {
        resolution.completed.forEach { operations.delete(it) }
    }

    /**
     * Called when a folder has been pulled completely; [afterReset] when it was downloaded anew,
     * in which case the stored flags are exactly the server's and the pending changes go over them.
     */
    suspend fun settle(accountId: Long, folderPath: String, afterReset: Boolean) {
        rebase(accountId, folderPath)
        if (afterReset) overlayFlags(accountId, folderPath)
        val waiting = operations.all(accountId).filter {
            it.folderPath == folderPath
        }.map { it.uid }.toSet()
        messages.pendingUids(accountId, folderPath)
            .filter { it !in waiting }
            .forEach { messages.setPendingSync(accountId, folderPath, it, pending = false) }
        waiting.forEach { messages.setPendingSync(accountId, folderPath, it, pending = true) }
    }

    private suspend fun overlayFlags(accountId: Long, folderPath: String) {
        flagOperations(accountId, folderPath).forEach { (uid, pending) ->
            val row = messages.get(accountId, folderPath, uid) ?: return@forEach
            val resolution = FlagResolver.resolve(
                Flags(row.seen, row.flagged, row.answered),
                pending
            )
            messages.setFlags(
                row.id,
                resolution.merged.seen,
                resolution.merged.flagged,
                resolution.merged.answered,
                pendingSync = true
            )
            complete(resolution)
        }
    }

    private suspend fun rebase(accountId: Long, folderPath: String) {
        operations.all(accountId)
            .filter {
                it.folderPath == folderPath && it.uid <= 0 && !it.failed &&
                    it.type.isServerOperation()
            }
            .forEach { operation ->
                val placeholder = messages.get(accountId, folderPath, operation.uid)
                val identity = MessageIdentity(placeholder?.messageId, placeholder?.gmailMessageId)
                val found = if (identity.isEmpty()) {
                    emptyList()
                } else {
                    messages.withIdentity(
                        accountId,
                        identity.messageId,
                        identity.gmailMessageId
                    ).map {
                        ServerMessage(
                            it.folderPath,
                            it.uid,
                            MessageIdentity(it.messageId, it.gmailMessageId),
                            it.labels.toSet()
                        )
                    }
                }
                settleOperation(operation, identity, found)
                // The placeholder only carried the message across the reset.
                if (operations.countForMessage(accountId, folderPath, operation.uid) == 0) {
                    messages.delete(accountId, folderPath, operation.uid)
                }
            }
    }

    private suspend fun settleOperation(
        operation: PendingOperationEntity,
        identity: MessageIdentity,
        found: List<ServerMessage>
    ) {
        val kind = operation.type.toTargetKind()
        if (kind == null) {
            // Flags: the message is wherever it is again in the same folder.
            val newUid = found.firstOrNull { it.folderPath == operation.folderPath }?.uid
            if (newUid != null) {
                operations.rebase(operation.id, newUid)
            } else {
                vanished(operation)
            }
            return
        }
        val targeted = TargetedOperation(
            operation.id,
            operation.accountId,
            kind,
            operation.folderPath,
            operation.uid,
            identity,
            operation.payload
        )
        when (val resolution = TargetResolver.resolve(targeted, found)) {
            is TargetResolution.Apply -> operations.rebase(operation.id, resolution.uid)

            TargetResolution.AlreadyApplied -> operations.delete(operation.id)

            is TargetResolution.Discard -> {
                operations.delete(operation.id)
                notices.publish(resolution.notice)
            }
        }
    }

    private suspend fun vanished(operation: PendingOperationEntity) {
        operations.delete(operation.id)
        notices.publish(
            SyncNotice.MessageVanished(operation.accountId, operation.folderPath, operation.id)
        )
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private fun PendingOperationEntity.toFlagOperation(): PendingFlagOperation? {
        if (type != OperationType.SET_FLAGS || failed) return null
        val change = runCatching { FlagChange.decode(payload) }.getOrNull() ?: return null
        return PendingFlagOperation(
            id,
            createdAt,
            FlagPatch(seen = change.seen, flagged = change.flagged)
        )
    }

    private companion object {
        const val DELETE_CHUNK = 500
    }
}

internal fun OperationType.isServerOperation() =
    this != OperationType.SEND && this != OperationType.SAVE_DRAFT

internal fun OperationType.toTargetKind(): TargetKind? = when (this) {
    OperationType.MOVE -> TargetKind.MOVE
    OperationType.ADD_LABEL -> TargetKind.ADD_LABEL
    OperationType.REMOVE_LABEL -> TargetKind.REMOVE_LABEL
    OperationType.DELETE -> TargetKind.DELETE
    OperationType.SET_FLAGS, OperationType.SAVE_DRAFT, OperationType.SEND -> null
}
