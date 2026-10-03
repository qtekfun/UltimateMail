// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/** A message row of the folder being checked; [unsyncedDraft] marks a draft not on the server. */
data class LocalMessage(
    val rowId: Long,
    val uid: Long,
    val identity: MessageIdentity,
    val unsyncedDraft: Boolean = false
)

/** A pending operation addressed to a message of the folder, by the UID it had. */
data class PendingTarget(val operationId: Long, val uid: Long)

/** Operation [operationId] must be found again by [identity] once the folder is downloaded. */
data class OperationRemap(val operationId: Long, val identity: MessageIdentity)

/**
 * What to do when a folder's UIDVALIDITY changed.
 *
 * @property dropRowIds local rows to delete; the folder is downloaded again.
 * @property keepDraftRowIds rows holding unsynced drafts, which must survive the reset.
 * @property remaps operations to keep, to be resolved by identity with [TargetResolver].
 * @property discardOperationIds operations that cannot be remapped (their row is unknown or has
 * no stable identity).
 * @property notices a [SyncNotice.FolderReset] plus one vanished notice per discarded operation.
 */
data class FolderResetPlan(
    val dropRowIds: List<Long>,
    val keepDraftRowIds: List<Long>,
    val remaps: List<OperationRemap>,
    val discardOperationIds: List<Long>,
    val notices: List<SyncNotice>
)

/** What is stored locally about a folder, with the UIDVALIDITY it was synced under (if any). */
data class FolderSnapshot(
    val accountId: Long,
    val folderPath: String,
    val storedUidValidity: Long?,
    val messages: List<LocalMessage>,
    val pending: List<PendingTarget>
)

/** Rule 4: UIDVALIDITY changed. */
object FolderResetPlanner {
    /**
     * Returns null when the folder is fine: never synced (no stored UIDVALIDITY) or the same
     * value as the server's. Otherwise the plan to invalidate it without losing work.
     */
    fun plan(folder: FolderSnapshot, serverUidValidity: Long): FolderResetPlan? {
        val stored = folder.storedUidValidity
        if (stored == null || stored == serverUidValidity) return null
        val (drafts, others) = folder.messages.partition { it.unsyncedDraft }
        val identified = folder.messages.filterNot { it.identity.isEmpty() }.associateBy { it.uid }
        val (remappable, lost) = folder.pending.partition { it.uid in identified }
        val reset = SyncNotice.FolderReset(folder.accountId, folder.folderPath)
        val vanished = { operationId: Long ->
            SyncNotice.MessageVanished(folder.accountId, folder.folderPath, operationId)
        }
        return FolderResetPlan(
            dropRowIds = others.map { it.rowId },
            keepDraftRowIds = drafts.map { it.rowId },
            remaps = remappable.map {
                OperationRemap(it.operationId, identified.getValue(it.uid).identity)
            },
            discardOperationIds = lost.map { it.operationId },
            notices = listOf<SyncNotice>(reset) + lost.map { vanished(it.operationId) }
        )
    }
}
