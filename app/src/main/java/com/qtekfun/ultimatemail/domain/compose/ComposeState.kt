// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.DraftState
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * How a message in the outbox is doing. No message content: [reason] is a code the UI maps to a
 * string (`network`, `timeout`, `auth_required`, `server_busy`, `confirm_sent`, `unexpected`
 * while waiting; `server_rejected`, `certificate`, `bad_payload`, `no_account`... when failed).
 */
sealed interface OutboxState {
    /** Waiting for the queue: offline, not yet its turn, or backing off after [reason]. */
    data class Queued(val attempts: Int, val nextAttemptAt: Instant?, val reason: String?) :
        OutboxState

    /** Handed to the server (or accepted by it and being tidied up) right now. */
    data object Sending : OutboxState

    /** Refused for good: offer retry, edit and discard (`OutboxActions`). */
    data class Failed(val reason: String) : OutboxState
}

/** A message in the outbox: the draft (for showing it) and how its send is going. */
data class OutboxEntry(val draft: Draft, val state: OutboxState) {
    override fun toString(): String = "OutboxEntry(draft=${draft.id}, state=$state)"
}

/** A copy of a draft kept in the server's Drafts folder that has no local draft. */
data class ServerDraft(
    /** The id of the row in Room: what the reader and the message actions use. */
    val messageRowId: Long,
    val accountId: Long,
    val folderPath: String,
    val uid: Long,
    val subject: String,
    val sentAt: Instant
) {
    override fun toString(): String = "ServerDraft(uid=$uid)"
}

/** One line of the Drafts list: a draft being written here, or one that is only on the server. */
sealed interface DraftListItem {
    val accountId: Long
    val time: Instant

    data class Local(val draft: Draft) : DraftListItem {
        override val accountId get() = draft.accountId
        override val time get() = draft.updatedAt
    }

    data class OnServer(val draft: ServerDraft) : DraftListItem {
        override val accountId get() = draft.accountId
        override val time get() = draft.sentAt
    }
}

/** What the drawer and the outbox badge show: how many of each, never what they say. */
data class ComposeCounts(val drafts: Int, val outbox: Int, val failed: Int)

/**
 * What the UI observes about drafts and sending (RF-07), per account or, with `accountId` null,
 * across all accounts. Everything comes from Room, so it is correct offline and after the
 * process was killed.
 */
class ComposeState @Inject constructor(
    private val drafts: DraftDao,
    private val messages: MessageDao,
    private val operations: PendingOperationDao
) {
    /**
     * The Drafts list: drafts being written on this device first-class, plus the copies in the
     * server's Drafts folders that are not one of them (written on another device, or by another
     * app), newest first. A draft's own server copy is not listed twice.
     */
    fun observeDrafts(accountId: Long? = null): Flow<List<DraftListItem>> = combine(
        accountId?.let { drafts.observeByState(it, DraftState.EDITING) }
            ?: drafts.observeAllByState(DraftState.EDITING),
        accountId?.let(messages::observeServerDrafts) ?: messages.observeAllServerDrafts()
    ) { local, server ->
        val keys = local.map { it.key }.toSet()
        val copies = local.mapNotNull { it.serverMessageId }.toSet()
        val own = local.map { DraftListItem.Local(it.toDraft()) }
        val others = server.filter { it.isForeignTo(keys, copies) }
            .map { DraftListItem.OnServer(it.toServerDraft()) }
        (own + others).sortedByDescending { it.time }
    }

    /** The messages waiting to be sent or failed, oldest first, with the state of each. */
    fun observeOutbox(accountId: Long? = null): Flow<List<OutboxEntry>> = combine(
        accountId?.let { drafts.observeByState(it, DraftState.OUTBOX) }
            ?: drafts.observeAllByState(DraftState.OUTBOX),
        accountId?.let(operations::observeSends) ?: operations.observeAllSends()
    ) { outbox, sends ->
        outbox.sortedBy { it.createdAt }.map { entity ->
            val send = sends.firstOrNull { it.uid == entity.id && it.accountId == entity.accountId }
            OutboxEntry(entity.toDraft(), stateOf(send, entity.smtpAcceptedAt != null))
        }
    }

    /** Counts for the drawer: drafts being written, messages in the outbox, and failed ones. */
    fun observeCounts(accountId: Long? = null): Flow<ComposeCounts> = combine(
        accountId?.let { drafts.observeCount(it, DraftState.EDITING) }
            ?: drafts.observeCountAll(DraftState.EDITING),
        observeOutbox(accountId)
    ) { count, outbox ->
        ComposeCounts(count, outbox.size, outbox.count { it.state is OutboxState.Failed })
    }.distinctUntilChanged()

    /** Just the outbox size, for a badge. */
    fun observeOutboxCount(accountId: Long? = null): Flow<Int> =
        observeOutbox(accountId).map { it.size }.distinctUntilChanged()

    private fun stateOf(send: PendingOperationEntity?, accepted: Boolean): OutboxState = when {
        accepted -> OutboxState.Sending

        // A queued draft without its operation: nothing will ever send it; the user can edit it.
        send == null -> OutboxState.Failed(NO_OPERATION)

        send.failed -> OutboxState.Failed(send.lastError ?: UNKNOWN)

        send.startedAt != null && send.lastError == null -> OutboxState.Sending

        else -> OutboxState.Queued(
            send.attempts,
            send.nextAttemptAt.takeIf { send.attempts > 0 },
            send.lastError
        )
    }

    private fun MessageEntity.isForeignTo(keys: Set<String>, copies: Set<String>) =
        messageId !in copies && DraftMessageIds.keyOf(messageId) !in keys

    private fun MessageEntity.toServerDraft() =
        ServerDraft(id, accountId, folderPath, uid, subject, sentAt)

    private companion object {
        const val NO_OPERATION = "no_operation"
        const val UNKNOWN = "unknown"
    }
}
