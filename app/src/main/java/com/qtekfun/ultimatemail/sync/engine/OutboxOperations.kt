// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.compose.DraftMessageIds
import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.OutgoingAttachment
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidRange
import com.qtekfun.ultimatemail.sync.conflict.DraftDecision
import com.qtekfun.ultimatemail.sync.conflict.DraftResolver
import com.qtekfun.ultimatemail.sync.conflict.LocalDraft
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome
import java.time.Clock
import javax.inject.Inject

/**
 * The server side of the composer (RF-07), used by [MailOperationExecutor] for the operations
 * that come from a draft:
 *
 * - [saveDraft] (SAVE_DRAFT): puts the new version of a draft in the Drafts folder and removes
 *   the old one by its Message-ID. The copies on the server are told apart by the draft key in
 *   their Message-ID ([DraftMessageIds]); [DraftResolver] decides what to do when another device
 *   changed the draft too: both versions are kept (this device's text continues as a new draft)
 *   and a [com.qtekfun.ultimatemail.sync.conflict.SyncNotice.DraftConflict] tells the user.
 * - [afterAccepted] (SEND): once SMTP took the message, the rest of the work: a copy in Sent for
 *   providers that do not file it themselves, the answered/forwarded flag on the message that was
 *   replied to, the removal of the draft's server copy, and finally of the draft and its files.
 *   The moment of acceptance is stored in the draft, so a retry never sends again.
 */
@Suppress("LongParameterList")
class OutboxOperations @Inject constructor(
    private val drafts: DraftDao,
    private val folders: FolderDao,
    private val messages: MessageDao,
    private val files: OutboxFileStorage,
    private val notices: SyncNotices,
    private val clock: Clock
) {
    internal suspend fun draft(id: Long): DraftEntity? = drafts.get(id)

    /** The message of [queued] with its attachment files read, or null if one is gone. */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    internal fun withAttachments(queued: QueuedMessage): OutgoingMessage? {
        val message = queued.message
        val loaded = queued.attachments.map {
            val bytes = files.read(it.path) ?: return null
            OutgoingAttachment(it.fileName, it.mimeType, bytes)
        }
        if (loaded.isEmpty()) return message
        return OutgoingMessage(
            from = message.from,
            to = message.to,
            cc = message.cc,
            bcc = message.bcc,
            subject = message.subject,
            text = message.text,
            html = message.html,
            attachments = loaded,
            inReplyTo = message.inReplyTo,
            references = message.references,
            messageId = message.messageId
        )
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    internal suspend fun saveDraft(
        operation: PendingOperationEntity,
        queued: QueuedMessage,
        session: MailSession
    ): OperationOutcome {
        val draft = queued.draftId?.let { drafts.get(it) }
        // Gone (discarded) or already handed to the send queue: nothing to keep on the server.
        if (draft == null || draft.state != DraftState.EDITING) return OperationOutcome.Done
        val folder = folders.all(operation.accountId).firstOrNull { it.role == FolderRole.DRAFTS }
            ?: return OperationOutcome.Done
        val copies = when (val found = session.newestHeaders(folder.path, RECENT_DRAFTS)) {
            is MailResult.Success ->
                found.value
                    .filter { DraftMessageIds.keyOf(it.messageId) == draft.key }
                    .map { ServerCopy(it.uid, it.messageId) }

            is MailResult.Failure -> return failureOutcome(found)
        }
        // The id of this version belongs to the key the draft has now (a fork changes the key).
        val message = queued.message.withId(
            queued.message.messageId?.takeIf { DraftMessageIds.keyOf(it) == draft.key }
                ?: DraftMessageIds.forServerCopy(draft.key, queued.message.from.address)
        )
        val target = Target(session, folder.path, draft, queued.revision, copies)
        // An earlier attempt stored this very version without us hearing back.
        if (copies.any { it.messageId.sameMessageId(message.messageId) }) {
            return finishSave(target, message.messageId)
        }
        val decision = DraftResolver.resolve(
            LocalDraft(operation.accountId, draft.key, draft.serverMessageId, dirty = true),
            copies.maxByOrNull { it.uid }?.messageId
        )
        return when (decision) {
            DraftDecision.UploadLocal -> upload(target, message)

            is DraftDecision.KeepBoth -> fork(target, message, decision)

            // A draft with a save waiting is dirty by definition, so these never come out.
            DraftDecision.AcceptServer, DraftDecision.DiscardLocal -> OperationOutcome.Done
        }
    }

    private suspend fun upload(target: Target, message: OutgoingMessage): OperationOutcome =
        when (val stored = target.session.appendDraft(target.folder, message)) {
            is MailResult.Success -> finishSave(target, message.messageId)
            is MailResult.Failure -> failureOutcome(stored)
        }

    /** A copy of a draft in the Drafts folder, by UID and Message-ID. */
    private class ServerCopy(val uid: Long, val messageId: String?)

    /** Where a save happens: the session, the Drafts folder, the draft and what is there. */
    private class Target(
        val session: MailSession,
        val folder: String,
        val draft: DraftEntity,
        val revision: Int,
        val copies: List<ServerCopy>
    )

    /**
     * Both devices changed the draft: the server copy stays as it is (it is the other device's
     * draft) and this device's text goes on as a draft of its own, under a new key.
     */
    private suspend fun fork(
        target: Target,
        message: OutgoingMessage,
        decision: DraftDecision.KeepBoth
    ): OperationOutcome {
        val key = DraftMessageIds.newKey()
        val newId = DraftMessageIds.forServerCopy(key, message.from.address)
        // The new key and the id about to be written are stored first, so that a retry after a
        // lost answer finds its own copy instead of forking again.
        drafts.rekey(target.draft.id, key)
        drafts.markUploaded(target.draft.id, newId, UNCHANGED)
        notices.publish(decision.notice)
        return when (
            val stored = target.session.appendDraft(
                target.folder,
                message.withId(newId)
            )
        ) {
            is MailResult.Success -> {
                drafts.markUploaded(target.draft.id, newId, target.revision)
                OperationOutcome.Done
            }

            is MailResult.Failure -> failureOutcome(stored)
        }
    }

    /** Removes every copy of the draft except the new version, and records the new one. */
    private suspend fun finishSave(target: Target, newId: String?): OperationOutcome {
        val stale = target.copies.filter {
            !it.messageId.sameMessageId(newId)
        }.map { it.uid }.toSet()
        if (stale.isNotEmpty()) {
            val removed = target.session.delete(target.folder, stale)
            if (removed is MailResult.Failure) return failureOutcome(removed)
        }
        newId?.let { drafts.markUploaded(target.draft.id, it, target.revision) }
        return OperationOutcome.Done
    }

    /** What is left to do once SMTP accepted [message]; see the class comment. */
    internal suspend fun afterAccepted(
        session: MailSession,
        account: AccountEntity,
        queued: QueuedMessage,
        message: OutgoingMessage,
        attempts: Int
    ): OperationOutcome {
        val draftId = checkNotNull(queued.draftId)
        drafts.markSmtpAccepted(draftId, clock.instant())
        val steps = listOf<suspend () -> MailResult<*>>(
            { storeSentCopy(session, account, message) },
            { markSource(session, account.id, queued) },
            { removeServerDraft(session, account, queued) }
        )
        for (step in steps) {
            val result = step()
            // The message is out; tidying up that keeps failing must not keep it in the outbox.
            if (result is MailResult.Failure && result.worthRetrying() &&
                attempts < MAX_TIDY_ATTEMPTS
            ) {
                return failureOutcome(result)
            }
        }
        drafts.delete(draftId)
        files.deleteDraft(draftId)
        return OperationOutcome.Done
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun storeSentCopy(
        session: MailSession,
        account: AccountEntity,
        message: OutgoingMessage
    ): MailResult<*> {
        if (SentCopyPolicy.providerKeepsSentMail(account)) return MailResult.Success(Unit)
        val sent = folders.all(account.id).firstOrNull { it.role == FolderRole.SENT }
            ?: return MailResult.Success(Unit)
        return when (val held = session.holdsMessageId(sent.path, message.messageId)) {
            is MailResult.Success ->
                if (held.value) held else session.appendSent(sent.path, message)

            MailResult.NotFound -> MailResult.Success(Unit)

            is MailResult.Failure -> held
        }
    }

    /** Sets `\Answered` or `$Forwarded` on the message replied to, if it is still that message. */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun markSource(
        session: MailSession,
        accountId: Long,
        queued: QueuedMessage
    ): MailResult<*> {
        val source = queued.source?.takeIf { it.accountId == accountId }
            ?: return MailResult.Success(Unit)
        val header = when (
            val found = session.fetchHeaders(
                source.folderPath,
                UidRange(source.uid, source.uid)
            )
        ) {
            is MailResult.Success -> found.value.firstOrNull()
            MailResult.NotFound -> null
            is MailResult.Failure -> return found
        }
        // A UID can change hands after a UIDVALIDITY reset: the Message-ID must still match.
        if (header == null || source.messageId == null ||
            !header.messageId.sameMessageId(source.messageId)
        ) {
            return MailResult.Success(Unit)
        }
        val flag = if (source.forwarded) MailFlag.FORWARDED else MailFlag.ANSWERED
        val result = session.setFlags(source.folderPath, setOf(source.uid), setOf(flag), true)
        if (result is MailResult.Success && !source.forwarded) {
            messages.get(source.accountId, source.folderPath, source.uid)?.let {
                messages.setFlags(
                    it.id,
                    it.seen,
                    it.flagged,
                    answered = true,
                    pendingSync = it.pendingSync
                )
            }
        }
        return result
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun removeServerDraft(
        session: MailSession,
        account: AccountEntity,
        queued: QueuedMessage
    ): MailResult<*> {
        val key = queued.draftKey ?: return MailResult.Success(Unit)
        val folder = folders.all(account.id).firstOrNull { it.role == FolderRole.DRAFTS }
            ?: return MailResult.Success(Unit)
        val uids = when (val found = session.newestHeaders(folder.path, RECENT_DRAFTS)) {
            is MailResult.Success ->
                found.value.filter {
                    DraftMessageIds.keyOf(it.messageId) == key
                }.map { it.uid }.toSet()

            MailResult.NotFound -> emptySet()

            is MailResult.Failure -> return found
        }
        return if (uids.isEmpty()) MailResult.Success(Unit) else session.delete(folder.path, uids)
    }

    private companion object {
        /** Passed to `markUploaded` to record the id without saying the text was uploaded. */
        const val UNCHANGED = -1

        /** After this many attempts, a Sent copy or flag that still fails is given up on. */
        const val MAX_TIDY_ATTEMPTS = 6
    }
}

private fun OutgoingMessage.withId(id: String) = OutgoingMessage(
    from = from,
    to = to,
    cc = cc,
    bcc = bcc,
    subject = subject,
    text = text,
    html = html,
    attachments = attachments,
    inReplyTo = inReplyTo,
    references = references,
    messageId = id
)

private fun MailResult.Failure.worthRetrying() = this == MailResult.NetworkUnavailable ||
    this == MailResult.Timeout || this == MailResult.AuthenticationFailed ||
    (this is MailResult.ServerRejected && !permanent)

/**
 * Which providers file a copy of every message sent through their SMTP server in the Sent
 * folder themselves, so that the app must not add a second one. Gmail does; so does Outlook.com
 * and Microsoft 365. Every other server gets an IMAP APPEND to the Sent folder, if the account
 * has one.
 */
internal object SentCopyPolicy {
    private val hosts = setOf(
        "smtp.gmail.com",
        "smtp.googlemail.com",
        "imap.gmail.com",
        "imap.googlemail.com",
        "smtp.office365.com",
        "smtp-mail.outlook.com",
        "outlook.office365.com"
    )

    fun providerKeepsSentMail(account: AccountEntity): Boolean =
        account.authType != AuthType.PASSWORD ||
            account.smtpHost.trim().lowercase() in hosts ||
            account.imapHost.trim().lowercase() in hosts
}
