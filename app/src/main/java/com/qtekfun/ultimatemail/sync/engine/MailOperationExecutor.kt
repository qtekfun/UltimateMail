// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidOperationResult
import com.qtekfun.ultimatemail.domain.mail.UidRange
import com.qtekfun.ultimatemail.sync.conflict.MessageIdentity
import com.qtekfun.ultimatemail.sync.conflict.SendAttempt
import com.qtekfun.ultimatemail.sync.conflict.SendDecision
import com.qtekfun.ultimatemail.sync.conflict.SendResolver
import com.qtekfun.ultimatemail.sync.conflict.SentLookup
import com.qtekfun.ultimatemail.sync.conflict.ServerMessage
import com.qtekfun.ultimatemail.sync.conflict.TargetKind
import com.qtekfun.ultimatemail.sync.conflict.TargetResolution
import com.qtekfun.ultimatemail.sync.conflict.TargetResolver
import com.qtekfun.ultimatemail.sync.conflict.TargetedOperation
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.OperationExecutor
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends queued operations to the server over IMAP and SMTP (SPEC section 5, rule 5).
 *
 * Every operation is safe to repeat: flags are absolute; move, label and delete go through
 * [TargetResolver], which recognises work an earlier attempt already did and a message that has
 * vanished (the user gets a [SyncNotices] notice); a SEND looks for its Message-ID in the Sent
 * folder before every attempt and after an ambiguous failure ([SendResolver]), so a message the
 * server accepted but never confirmed is not sent twice.
 *
 * Failures become [OperationOutcome]s: network trouble, timeouts and transient server answers
 * retry later; a refusal for good, an untrusted certificate or a missing folder are rejected;
 * a login that no longer works retries later and the account asks the user to sign in again.
 */
// One small function per operation kind, and the collaborators each kind needs.
@Suppress("TooManyFunctions", "LongParameterList")
@Singleton
class MailOperationExecutor @Inject constructor(
    private val accounts: AccountDao,
    private val folders: FolderDao,
    private val messages: MessageDao,
    private val sessions: AccountSessions,
    private val credentials: MailCredentialsProvider,
    private val sender: MailSender,
    private val marker: PendingSyncMarker,
    private val status: SyncStatusStore,
    private val notices: SyncNotices
) : OperationExecutor {
    override suspend fun execute(operation: PendingOperationEntity): OperationOutcome {
        val outcome = if (operation.uid <= 0 && operation.type.refersToServerMessage) {
            // The message still waits for the resync after a UIDVALIDITY reset to get a new UID.
            OperationOutcome.RetryLater(NOT_SYNCED)
        } else {
            onServer(operation)
        }
        if (outcome == OperationOutcome.Done) marker.clearIfIdle(operation)
        return outcome
    }

    private suspend fun onServer(operation: PendingOperationEntity): OperationOutcome {
        val leased = sessions.withSession(operation.accountId) { run(operation, it) }
        return when (leased) {
            is Leased.Ok -> leased.value
            Leased.AuthRequired -> OperationOutcome.RetryLater(AUTH_REQUIRED)
            is Leased.Failed -> failure(leased.failure)
            Leased.NoAccount -> OperationOutcome.Rejected(NO_ACCOUNT)
        }
    }

    private suspend fun run(
        operation: PendingOperationEntity,
        session: MailSession
    ): OperationOutcome = when (operation.type) {
        OperationType.SET_FLAGS -> setFlags(operation, session)

        OperationType.MOVE, OperationType.ADD_LABEL, OperationType.REMOVE_LABEL,
        OperationType.DELETE -> targeted(operation, session)

        OperationType.SAVE_DRAFT -> saveDraft(operation, session)

        OperationType.SEND -> send(operation, session)
    }

    /**
     * Move, label and delete name a message by UID; before sending, [TargetResolver] checks that
     * the UID is still that message, whether the work is already done (an earlier attempt whose
     * answer was lost) or the message is gone, which the user is told about (SPEC section 5).
     */
    private suspend fun targeted(
        operation: PendingOperationEntity,
        session: MailSession
    ): OperationOutcome {
        val kind = checkNotNull(operation.type.toTargetKind())
        val row = messages.get(operation.accountId, operation.folderPath, operation.uid)
        val request = TargetedOperation(
            operation.id,
            operation.accountId,
            kind,
            operation.folderPath,
            operation.uid,
            MessageIdentity(row?.messageId, row?.gmailMessageId),
            operation.payload
        )
        var known = when (
            val source = serverMessages(
                session,
                operation.folderPath,
                operation.uid..operation.uid
            )
        ) {
            is MailResult.Success -> source.value
            is MailResult.Failure -> return failure(source)
        }
        var resolution = TargetResolver.resolve(request, known)
        if (resolution is TargetResolution.Discard && kind == TargetKind.MOVE) {
            // Gone from the source: it may have arrived at the destination on an earlier attempt.
            val latest = recentRange(session, operation.payload)
            if (latest is MailResult.Success) known = known + latest.value
            resolution = TargetResolver.resolve(request, known)
        }
        return when (resolution) {
            is TargetResolution.Apply -> perform(operation, session, resolution.uid)

            TargetResolution.AlreadyApplied -> OperationOutcome.Done

            is TargetResolution.Discard -> {
                notices.publish(resolution.notice)
                OperationOutcome.Done
            }
        }
    }

    private suspend fun perform(
        operation: PendingOperationEntity,
        session: MailSession,
        uid: Long
    ): OperationOutcome {
        val folder = operation.folderPath
        val uids = setOf(uid)
        return when (operation.type) {
            OperationType.MOVE -> done(session.move(folder, uids, operation.payload)).also {
                // The list already hides the message (RF-06); with the server done, the row of
                // the old folder goes, so it cannot show again before the next pull of it.
                if (it == OperationOutcome.Done) {
                    messages.delete(operation.accountId, folder, operation.uid)
                }
            }

            OperationType.ADD_LABEL -> done(
                session.addLabels(folder, uids, setOf(operation.payload))
            )

            OperationType.REMOVE_LABEL -> done(
                session.removeLabels(folder, uids, setOf(operation.payload))
            )

            else -> done(session.delete(folder, uids))
        }
    }

    private suspend fun serverMessages(
        session: MailSession,
        folder: String,
        uids: LongRange
    ): MailResult<List<ServerMessage>> =
        when (val headers = session.fetchHeaders(folder, UidRange(uids.first, uids.last))) {
            is MailResult.Success -> MailResult.Success(
                headers.value.map {
                    it.toServerMessage(folder)
                }
            )

            is MailResult.Failure -> headers
        }

    /** The newest messages of [folder], for finding a message that was moved there. */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun recentRange(
        session: MailSession,
        folder: String
    ): MailResult<List<ServerMessage>> {
        val status = when (val result = session.folderStatus(folder)) {
            is MailResult.Success -> result.value
            is MailResult.Failure -> return result
        }
        val top = status.uidNext - 1
        if (top < 1) return MailResult.Success(emptyList())
        return serverMessages(session, folder, maxOf(1, top - RECENT + 1)..top)
    }

    private fun MessageHeader.toServerMessage(folder: String) = ServerMessage(
        folder,
        uid,
        MessageIdentity(messageId, gmail?.messageId),
        gmail?.labels.orEmpty().toSet()
    )

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun setFlags(
        operation: PendingOperationEntity,
        session: MailSession
    ): OperationOutcome {
        // A payload this version cannot read will not become readable by waiting.
        val change = runCatching { FlagChange.decode(operation.payload) }.getOrNull()
            ?: return OperationOutcome.Rejected(BAD_PAYLOAD)
        // A changed UIDVALIDITY means this UID may be another message now: the pull sorts it out.
        val stored = folders.get(operation.accountId, operation.folderPath)?.uidValidity
        if (stored != null) {
            when (val current = session.folderStatus(operation.folderPath)) {
                is MailResult.Success ->
                    if (current.value.uidValidity != stored) {
                        return OperationOutcome.RetryLater(FOLDER_RESET)
                    }

                is MailResult.Failure -> return failure(current)
            }
        }
        val uids = setOf(operation.uid)
        val steps = listOf(MailFlag.SEEN to change.seen, MailFlag.FLAGGED to change.flagged)
        for ((flag, enabled) in steps) {
            if (enabled == null) continue
            val outcome = done(session.setFlags(operation.folderPath, uids, setOf(flag), enabled))
            if (outcome != OperationOutcome.Done) return outcome
        }
        return OperationOutcome.Done
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun saveDraft(
        operation: PendingOperationEntity,
        session: MailSession
    ): OperationOutcome {
        val message = OutgoingPayload.decode(operation.payload)
            ?: return OperationOutcome.Rejected(BAD_PAYLOAD)
        // An earlier attempt may have stored it without us hearing back.
        when (val exists = holds(session, operation.folderPath, message)) {
            is MailResult.Success -> if (exists.value) return OperationOutcome.Done
            MailResult.NotFound -> Unit
            is MailResult.Failure -> return failure(exists)
        }
        return when (val stored = session.appendDraft(operation.folderPath, message)) {
            is MailResult.Success -> OperationOutcome.Done
            is MailResult.Failure -> failure(stored)
        }
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun send(
        operation: PendingOperationEntity,
        session: MailSession
    ): OperationOutcome {
        val account =
            accounts.get(operation.accountId) ?: return OperationOutcome.Rejected(NO_ACCOUNT)
        val message = OutgoingPayload.decode(operation.payload)
            ?: return OperationOutcome.Rejected(BAD_PAYLOAD)
        // Never send blind: an earlier attempt may have been accepted without us hearing back, so
        // Sent is checked first, and if it cannot be read the message waits (SPEC section 5.5).
        val beforeSending = SendResolver.resolve(
            SendAttempt.AMBIGUOUS,
            sentLookup(session, account.id, message)
        )
        when (beforeSending) {
            SendDecision.Done -> return OperationOutcome.Done
            SendDecision.ConfirmFirst -> return OperationOutcome.RetryLater(CONFIRM_SENT)
            SendDecision.Retry -> Unit
        }
        val login = when (val result = credentials.forAccount(account)) {
            is CredentialsResult.Ready -> result.credentials

            CredentialsResult.ReauthenticationNeeded -> {
                status.set(account.id, AccountSyncState.ReauthenticationNeeded)
                return OperationOutcome.RetryLater(AUTH_REQUIRED)
            }

            CredentialsResult.TemporarilyUnavailable -> return OperationOutcome.RetryLater(NETWORK)
        }
        return when (val sent = sender.send(account.smtpServer(), login, message)) {
            is MailResult.Success -> OperationOutcome.Done
            is MailResult.Failure -> afterFailedSend(session, account.id, message, sent)
        }
    }

    /** A failure that may still have delivered the message is checked against Sent right away. */
    private suspend fun afterFailedSend(
        session: MailSession,
        accountId: Long,
        message: OutgoingMessage,
        failed: MailResult.Failure
    ): OperationOutcome {
        val ambiguous = failed == MailResult.NetworkUnavailable || failed == MailResult.Timeout ||
            failed == MailResult.Unknown || failed == MailResult.Protocol
        if (!ambiguous) return failure(failed)
        return when (
            SendResolver.resolve(
                SendAttempt.AMBIGUOUS,
                sentLookup(session, accountId, message)
            )
        ) {
            SendDecision.Done -> OperationOutcome.Done
            SendDecision.ConfirmFirst -> OperationOutcome.RetryLater(CONFIRM_SENT)
            SendDecision.Retry -> failure(failed)
        }
    }

    private suspend fun sentLookup(
        session: MailSession,
        accountId: Long,
        message: OutgoingMessage
    ): SentLookup {
        val sentFolder = folders.all(accountId).firstOrNull { it.role == FolderRole.SENT }
            ?: return SentLookup.NOT_FOUND
        return when (val found = holds(session, sentFolder.path, message)) {
            is MailResult.Success -> if (found.value) SentLookup.FOUND else SentLookup.NOT_FOUND

            // The Sent folder is gone: there is nothing to find.
            MailResult.NotFound -> SentLookup.NOT_FOUND

            is MailResult.Failure -> SentLookup.UNAVAILABLE
        }
    }

    /** Whether [folder] holds a message with the Message-ID of [message] among its newest ones. */
    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun holds(
        session: MailSession,
        folder: String,
        message: OutgoingMessage
    ): MailResult<Boolean> {
        val status = when (val result = session.folderStatus(folder)) {
            is MailResult.Success -> result.value
            is MailResult.Failure -> return result
        }
        val top = status.uidNext - 1
        if (top < 1) return MailResult.Success(false)
        val range = UidRange(maxOf(1, top - RECENT + 1), top)
        return when (val headers = session.fetchHeaders(folder, range)) {
            is MailResult.Success -> MailResult.Success(
                headers.value.any { it.messageId.sameId(message.messageId) }
            )

            is MailResult.Failure -> headers
        }
    }

    private val OperationType.refersToServerMessage: Boolean
        get() = this != OperationType.SAVE_DRAFT && this != OperationType.SEND

    private fun String?.sameId(other: String?) =
        this != null && other != null && trim().trim('<', '>') == other.trim().trim('<', '>')

    /** Applying to a message that is no longer there is success: see the class comment. */
    private fun done(result: MailResult<UidOperationResult>): OperationOutcome = when (result) {
        is MailResult.Success -> OperationOutcome.Done
        is MailResult.Failure -> failure(result)
    }

    private fun failure(failure: MailResult.Failure): OperationOutcome = when (failure) {
        MailResult.NetworkUnavailable -> OperationOutcome.RetryLater(NETWORK)

        MailResult.Timeout -> OperationOutcome.RetryLater(TIMEOUT)

        // The login may only need the user; the operation waits and the account says so.
        MailResult.AuthenticationFailed -> OperationOutcome.RetryLater(AUTH_REQUIRED)

        MailResult.CertificateRejected -> OperationOutcome.Rejected(CERTIFICATE)

        is MailResult.ServerRejected ->
            if (failure.permanent) {
                OperationOutcome.Rejected(SERVER_REJECTED)
            } else {
                OperationOutcome.RetryLater(SERVER_BUSY)
            }

        MailResult.NotFound -> OperationOutcome.Rejected(NOT_FOUND)

        is MailResult.Unsupported -> OperationOutcome.Rejected(UNSUPPORTED)

        MailResult.Protocol, MailResult.Unknown -> OperationOutcome.RetryLater(UNEXPECTED)
    }

    private companion object {
        /** How many of the newest messages of Sent or Drafts are searched for a Message-ID. */
        const val RECENT = 50L

        const val NETWORK = "network"
        const val TIMEOUT = "timeout"
        const val AUTH_REQUIRED = "auth_required"
        const val CERTIFICATE = "certificate"
        const val SERVER_REJECTED = "server_rejected"
        const val SERVER_BUSY = "server_busy"
        const val NOT_FOUND = "not_found"
        const val UNSUPPORTED = "unsupported"
        const val UNEXPECTED = "unexpected"
        const val FOLDER_RESET = "folder_reset"
        const val CONFIRM_SENT = "confirm_sent"
        const val NOT_SYNCED = "not_synced"
        const val BAD_PAYLOAD = "bad_payload"
        const val NO_ACCOUNT = "no_account"
    }
}
