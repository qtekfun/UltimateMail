// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How a request on an outbox message ended. */
enum class OutboxChange {
    DONE,

    /** No such message in the outbox. */
    MISSING,

    /**
     * The server may already have the message (a send was handed over and has not failed for
     * good): taking it back could send it twice, so it is refused until the outcome is known.
     */
    MAY_BE_SENT
}

/**
 * What the user can do with a message in the outbox (RF-07): retry a failed send, take it back
 * to edit it, or discard it. Only a message that certainly did not go out can be taken back or
 * discarded: one that never reached the server (still queued, for example offline) or that the
 * server refused for good. One that was handed over and is waiting for a retry might have been
 * delivered, so [OutboxChange.MAY_BE_SENT] protects against sending twice.
 */
class OutboxActions @Inject constructor(
    private val drafts: DraftDao,
    private val operations: PendingOperationDao,
    private val queue: OperationQueue,
    private val repository: DraftRepository,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** Tries a failed send again now. Does nothing for a message that has not failed. */
    suspend fun retry(draftId: Long): OutboxChange = withContext(io) {
        val draft = drafts.get(draftId) ?: return@withContext OutboxChange.MISSING
        val send = sendOf(draft.accountId, draftId) ?: return@withContext OutboxChange.MISSING
        queue.retry(send.id)
        scheduler.requestSync(draft.accountId, userInitiated = true)
        OutboxChange.DONE
    }

    /** Takes the message out of the queue and back to the composer as an editable draft. */
    suspend fun editAgain(draftId: Long): OutboxChange = withContext(io) {
        val draft = drafts.get(draftId)?.takeIf { it.state == DraftState.OUTBOX }
            ?: return@withContext OutboxChange.MISSING
        val send = sendOf(draft.accountId, draftId)
        if (draft.smtpAcceptedAt != null || send?.isUncertain() == true) {
            return@withContext OutboxChange.MAY_BE_SENT
        }
        send?.let { queue.discard(it.id) }
        drafts.update(
            draft.copy(
                state = DraftState.EDITING,
                // A new send is a new message: it gets a new Message-ID.
                outgoingMessageId = null,
                dirty = true,
                revision = draft.revision + 1,
                updatedAt = clock.instant()
            )
        )
        OutboxChange.DONE
    }

    /** Throws an outbox message away for good, with its attachment files. */
    suspend fun discard(draftId: Long): OutboxChange = withContext(io) {
        val draft = drafts.get(draftId)?.takeIf { it.state == DraftState.OUTBOX }
            ?: return@withContext OutboxChange.MISSING
        val send = sendOf(draft.accountId, draftId)
        if (draft.smtpAcceptedAt != null || send?.isUncertain() == true) {
            return@withContext OutboxChange.MAY_BE_SENT
        }
        send?.let { queue.discard(it.id) }
        repository.delete(draftId)
        OutboxChange.DONE
    }

    private suspend fun sendOf(accountId: Long, draftId: Long) =
        operations.forDraft(accountId, draftId, OperationType.SEND).firstOrNull()

    /** Handed to the server at least once and not refused for good: it may have gone out. */
    private fun PendingOperationEntity.isUncertain() = startedAt != null && !failed
}
