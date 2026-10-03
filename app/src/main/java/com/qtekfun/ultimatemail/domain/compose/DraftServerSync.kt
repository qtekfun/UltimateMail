// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Keeps the copy of a draft in the server's Drafts folder up to date (RF-07), with a SAVE_DRAFT
 * operation. It is deliberately lazy, because a mail server should not see every keystroke:
 *
 * - [request] is cheap to call after each local autosave. A save already waiting in the queue is
 *   refreshed in place (so the queue never holds more than one waiting save per draft); a new one
 *   is queued at most once per [MIN_INTERVAL] unless the caller forces it (the composer closing,
 *   the app going to the background).
 * - The operation runs with the next sync; [request] with `force` also asks for one.
 * - The executor (`MailOperationExecutor`) puts the new version in Drafts, removes the previous
 *   one by its Message-ID and keeps both when the draft was edited on two devices as well.
 *
 * Copies on the server carry no attachments (they would be uploaded on every save).
 */
@Singleton
class DraftServerSync @Inject constructor(
    private val drafts: DraftDao,
    private val accounts: AccountDao,
    private val folders: FolderDao,
    private val messages: MessageDao,
    private val operations: PendingOperationDao,
    private val queue: OperationQueue,
    private val marker: PendingSyncMarker,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val lastQueued = ConcurrentHashMap<Long, Instant>()

    /**
     * Makes sure the latest saved text of the draft will reach the server. Returns whether a save
     * is now waiting in the queue (false: nothing to upload, no Drafts folder, or throttled).
     */
    suspend fun request(draftId: Long, force: Boolean = false): Boolean = withContext(io) {
        val entity = drafts.get(draftId)
        val account = entity?.let { accounts.get(it.accountId) }
        val hasFolder = account != null &&
            folders.all(account.id).any { it.role == FolderRole.DRAFTS }
        if (entity == null || account == null || !hasFolder ||
            entity.state != DraftState.EDITING || (!entity.dirty && !force)
        ) {
            return@withContext false
        }
        val draft = entity.toDraft()
        val id = DraftMessageIds.forServerCopy(draft.key, account.email)
        val payload = OutgoingPayload.encode(
            DraftMessages.queued(draft, account, id, emptyList(), false)
        )
        val waiting = operations.forDraft(account.id, draftId, OperationType.SAVE_DRAFT)
            .firstOrNull { it.startedAt == null }
        val refreshed = waiting != null && operations.replacePayload(waiting.id, payload) > 0
        val now = clock.instant()
        val last = lastQueued[draftId]
        val throttled = !force && last != null && Duration.between(last, now) < MIN_INTERVAL
        val queued = refreshed || (!throttled && enqueue(account.id, draftId, payload, now))
        if (queued && force) scheduler.requestSync(account.id)
        queued
    }

    private suspend fun enqueue(
        accountId: Long,
        draftId: Long,
        payload: String,
        now: Instant
    ): Boolean {
        queue.enqueue(
            NewOperation(accountId, OperationType.SAVE_DRAFT, OUTBOX_FOLDER, draftId, payload)
        )
        lastQueued[draftId] = now
        return true
    }

    /**
     * Drops what is waiting to be saved for [draft] and, if the server holds a copy this device
     * knows about, queues its deletion (the user discarded the draft or moved it to another
     * account). A copy that was never synced down to Room cannot be named and stays on the
     * server, where it shows up in the Drafts list for the user to delete.
     */
    suspend fun forgetServerCopy(draft: Draft) = withContext(io) {
        operations.deleteForDraft(draft.accountId, draft.id, OperationType.SAVE_DRAFT)
        lastQueued.remove(draft.id)
        val copy = draft.serverMessageId ?: return@withContext
        var queued = false
        folders.all(draft.accountId).filter { it.role == FolderRole.DRAFTS }.forEach { folder ->
            messages.withIdentity(draft.accountId, copy, null)
                .filter { it.folderPath == folder.path }
                .forEach {
                    queue.enqueue(
                        NewOperation(
                            draft.accountId,
                            OperationType.DELETE,
                            it.folderPath,
                            it.uid,
                            ""
                        )
                    )
                    marker.mark(draft.accountId, it.folderPath, it.uid)
                    queued = true
                }
        }
        if (queued) scheduler.requestSync(draft.accountId)
    }

    companion object {
        /**
         * The "folder" of the operations that belong to a draft, not to a server message: they
         * use the draft id as their uid, so a SEND and a SAVE_DRAFT of one draft keep their order.
         */
        const val OUTBOX_FOLDER = ""

        /** The least time between two server saves of the same draft, unless forced. */
        val MIN_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
