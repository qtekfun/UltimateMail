// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidOperationResult
import com.qtekfun.ultimatemail.domain.mail.UidRange
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.OperationExecutor
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends queued operations to the server over IMAP and SMTP (SPEC section 5, rule 5).
 *
 * Every operation is safe to repeat: flags are absolute, and a message that is no longer there
 * (already moved or deleted by an earlier attempt whose answer was lost) counts as done. A SEND
 * looks for its Message-ID in the Sent folder before every attempt, so a message the server
 * accepted but never confirmed is not sent twice.
 *
 * Failures become [OperationOutcome]s: network trouble, timeouts and transient server answers
 * retry later; a refusal for good, an untrusted certificate or a missing folder are rejected;
 * a login that no longer works retries later and the account asks the user to sign in again.
 */
@Singleton
class MailOperationExecutor @Inject constructor(
    private val accounts: AccountDao,
    private val folders: FolderDao,
    private val sessions: AccountSessions,
    private val credentials: MailCredentialsProvider,
    private val sender: MailSender,
    private val marker: PendingSyncMarker,
    private val status: SyncStatusStore
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

    private suspend fun run(operation: PendingOperationEntity, session: MailSession): OperationOutcome {
        val folder = operation.folderPath
        val uids = setOf(operation.uid)
        return when (operation.type) {
            OperationType.SET_FLAGS -> setFlags(operation, session)

            OperationType.MOVE -> moved(session.move(folder, uids, operation.payload))

            OperationType.ADD_LABEL -> moved(session.addLabels(folder, uids, setOf(operation.payload)))

            OperationType.REMOVE_LABEL ->
                moved(session.removeLabels(folder, uids, setOf(operation.payload)))

            OperationType.DELETE -> moved(session.delete(folder, uids))

            OperationType.SAVE_DRAFT -> saveDraft(operation, session)

            OperationType.SEND -> send(operation, session)
        }
    }

    private suspend fun setFlags(operation: PendingOperationEntity, session: MailSession): OperationOutcome {
        // A payload this version cannot read will not become readable by waiting.
        val change = runCatching { FlagChange.decode(operation.payload) }.getOrNull()
            ?: return OperationOutcome.Rejected(BAD_PAYLOAD)
        val uids = setOf(operation.uid)
        val steps = listOf(MailFlag.SEEN to change.seen, MailFlag.FLAGGED to change.flagged)
        for ((flag, enabled) in steps) {
            if (enabled == null) continue
            val outcome = moved(session.setFlags(operation.folderPath, uids, setOf(flag), enabled))
            if (outcome != OperationOutcome.Done) return outcome
        }
        return OperationOutcome.Done
    }

    private suspend fun saveDraft(operation: PendingOperationEntity, session: MailSession): OperationOutcome {
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

    private suspend fun send(operation: PendingOperationEntity, session: MailSession): OperationOutcome {
        val account = accounts.get(operation.accountId) ?: return OperationOutcome.Rejected(NO_ACCOUNT)
        val message = OutgoingPayload.decode(operation.payload)
            ?: return OperationOutcome.Rejected(BAD_PAYLOAD)
        // Never send blind: an earlier attempt may have been accepted without us hearing back.
        // If Sent cannot be read now, wait. Without a Sent folder there is nothing to look in.
        val sentFolder = folders.all(account.id).firstOrNull { it.role == FolderRole.SENT }
        if (sentFolder != null) {
            when (val found = holds(session, sentFolder.path, message)) {
                is MailResult.Success -> if (found.value) return OperationOutcome.Done

                // The Sent folder is gone: nothing to find, so sending is the only way on.
                MailResult.NotFound -> Unit

                is MailResult.Failure -> return failure(found)
            }
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
            is MailResult.Failure -> failure(sent)
        }
    }

    /** Whether [folder] holds a message with the Message-ID of [message] among its newest ones. */
    private suspend fun holds(session: MailSession, folder: String, message: OutgoingMessage): MailResult<Boolean> {
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
    private fun moved(result: MailResult<UidOperationResult>): OperationOutcome = when (result) {
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
        const val NOT_SYNCED = "not_synced"
        const val BAD_PAYLOAD = "bad_payload"
        const val NO_ACCOUNT = "no_account"
    }
}
