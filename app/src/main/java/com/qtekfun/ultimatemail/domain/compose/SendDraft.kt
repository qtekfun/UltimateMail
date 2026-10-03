// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.OutgoingPayload
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How [SendDraft] ended. */
sealed interface SendResult {
    /** The draft is in the outbox, waiting for the queue; [operationId] is its SEND operation. */
    data class Queued(val operationId: Long) : SendResult

    /** There is no one to send it to. */
    data object NoRecipients : SendResult

    /** An attached file is gone from the outbox storage; the user has to attach it again. */
    data object AttachmentMissing : SendResult

    data object DraftMissing : SendResult

    /** The account of the draft was removed. */
    data object NoAccount : SendResult
}

/**
 * Sends a draft (RF-07): turns it into a SEND operation of the persistent queue, so that sending
 * works offline and survives the app being killed. Nothing here touches the network; the next
 * sync (requested here) hands the operation to the SMTP server.
 *
 * What it does, in an order that is safe if the process dies half way: drops the draft's waiting
 * server saves, fixes the Message-ID of the message (the same on every attempt, it is how a
 * message that may already have gone out is found in Sent), queues the SEND with everything
 * needed to rebuild the MIME message later (text, headers, attachment files, the message being
 * answered), and only then moves the draft to [DraftState.OUTBOX] so the composer can no longer
 * change it. Calling it again for a draft already queued returns the same operation.
 *
 * The composer should check [Draft.subject] being blank (and offer to send anyway) before calling
 * this: a message without subject is allowed.
 */
class SendDraft @Inject constructor(
    private val drafts: DraftDao,
    private val accounts: AccountDao,
    private val operations: PendingOperationDao,
    private val queue: OperationQueue,
    private val files: OutboxFileStorage,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    @Suppress("ReturnCount") // Each refusal leaves early; guard clauses keep the normal path flat.
    suspend operator fun invoke(draftId: Long): SendResult = withContext(io) {
        val entity = drafts.get(draftId) ?: return@withContext SendResult.DraftMissing
        val account = accounts.get(entity.accountId) ?: return@withContext SendResult.NoAccount
        val waiting = operations.forDraft(account.id, draftId, OperationType.SEND).firstOrNull()
        if (waiting != null) return@withContext SendResult.Queued(waiting.id)
        val draft = entity.toDraft()
        if (draft.recipients.isEmpty()) return@withContext SendResult.NoRecipients
        val attachments = drafts.attachments(draftId).map { it.toAttachment() }
        if (attachments.any { !files.exists(it.filePath) }) {
            return@withContext SendResult.AttachmentMissing
        }
        val messageId = DraftMessageIds.forSending(account.email)
        operations.deleteForDraft(account.id, draftId, OperationType.SAVE_DRAFT)
        val payload = OutgoingPayload.encode(
            DraftMessages.queued(draft, account, messageId, attachments, forSending = true)
        )
        val operationId = requireNotNull(
            queue.enqueue(
                NewOperation(
                    account.id,
                    OperationType.SEND,
                    DraftServerSync.OUTBOX_FOLDER,
                    draftId,
                    payload
                )
            )
        ) { "A send is never merged away" }
        drafts.update(
            entity.copy(
                state = DraftState.OUTBOX,
                outgoingMessageId = messageId,
                updatedAt = clock.instant()
            )
        )
        scheduler.requestSync(account.id)
        SendResult.Queued(operationId)
    }
}
