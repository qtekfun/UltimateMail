// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.FoundRow
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.mail.FolderStatus
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.search.LabelMatch
import com.qtekfun.ultimatemail.domain.search.SearchQuery
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.domain.search.ServerSearch
import com.qtekfun.ultimatemail.domain.search.ServerSearchCriteria
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure
import com.qtekfun.ultimatemail.domain.search.ServerSearchResult
import com.qtekfun.ultimatemail.domain.thread.MessageRef
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Search on the server (RF-09). For each account in the scope it connects, asks the server to
 * search the folders in scope (IMAP SEARCH, or Gmail's X-GM-RAW), and brings the hits the device
 * does not have into Room, so that they open, read and act like any other message.
 *
 * How server-only hits are stored: each one is inserted as a normal message of its folder, with
 * the headers fetched for it and no body, a conversation id from the same threading as synced
 * mail and its own flags. Nothing else changes: the folder's UIDNEXT and HIGHESTMODSEQ are never
 * written, so the next sync still fetches everything newer than it knew, skips the rows already
 * stored (by UID) and reconciles the old ones like the rest. One thing is written, and only for
 * a folder that never had a sync state: its UIDVALIDITY, so that a later sync notices if the
 * server renumbered the folder and drops these rows with the rest of the stale ones; with the
 * UIDNEXT still unknown the first sync of that folder is a full one, as it would have been.
 * The offline window prunes them: hits older than it are deleted by the next sync of the
 * account. In a folder that is not synced (Gmail's All Mail by default) no sync reconciles them,
 * so such hits keep the flags they had when found, until searched again or pruned.
 * Reading a hit loads its body on demand, through [LoadMessageBody], like any other message.
 *
 * Which folders: the one in a folder scope; otherwise, when the account has an All Mail folder
 * (Gmail), only that, as it holds every message once; otherwise the folders the user syncs,
 * without Trash and Spam. `in:` / `label:` pick the folders they name instead.
 */
class SearchOnServer @Inject internal constructor(
    private val accounts: AccountDao,
    private val folders: FolderDao,
    private val messages: MessageDao,
    private val sessions: AccountSessions,
    private val clock: Clock
) : ServerSearch {
    private class Tally {
        val rows = mutableListOf<FoundRow>()
        var added = 0
        var incomplete = false
        var searched = 0
        val failures = mutableListOf<ServerSearchFailure>()
    }

    private sealed interface FolderOutcome {
        class Hits(val rows: List<FoundRow>, val added: Int) : FolderOutcome

        /** Not searched: the folder changed on the server since the last sync. */
        data object Skipped : FolderOutcome

        class Failed(val failure: MailResult.Failure) : FolderOutcome
    }

    override suspend fun search(query: SearchQuery, scope: SearchScope): ServerSearchResult {
        val criteria = ServerSearchCriteria.of(query)
        val accountIds = scope.accountId?.let(::listOf)
            ?: accounts.observeAll().first().map { it.id }
        val tally = Tally()
        for (accountId in accountIds) {
            val plan = foldersFor(accountId, scope, query)
            if (plan.isEmpty()) continue
            tally.searched++
            searchAccount(accountId, plan, criteria, tally)
        }
        return resultOf(tally)
    }

    private fun resultOf(tally: Tally): ServerSearchResult = when {
        tally.searched == 0 -> ServerSearchResult.Failed(ServerSearchFailure.NOTHING_TO_SEARCH)

        tally.rows.isEmpty() && tally.failures.size == tally.searched ->
            ServerSearchResult.Failed(tally.failures.first())

        else -> ServerSearchResult.Found(
            messageIds = tally.rows.sortedByDescending { it.sentAt }.take(MAX_HITS).map { it.id },
            added = tally.added,
            incomplete = tally.incomplete || tally.failures.isNotEmpty()
        )
    }

    private suspend fun foldersFor(
        accountId: Long,
        scope: SearchScope,
        query: SearchQuery
    ): List<FolderEntity> {
        val all = folders.all(accountId)
        val allMail = all.filter { it.role == FolderRole.ALL_MAIL }
        return when {
            scope is SearchScope.Folder -> all.filter { it.path == scope.path }

            query.labels.isNotEmpty() -> all.filter { folder ->
                query.labels.any { LabelMatch.matches(it, folder.role, folder.name, folder.path) }
            }.ifEmpty { allMail }

            allMail.isNotEmpty() -> allMail

            else -> all.filter {
                it.syncEnabled && it.role != FolderRole.TRASH && it.role != FolderRole.JUNK
            }
        }
    }

    private suspend fun searchAccount(
        accountId: Long,
        plan: List<FolderEntity>,
        criteria: MailSearchCriteria,
        tally: Tally
    ) {
        val leased = sessions.withSession(accountId) { session ->
            val assigner = ThreadAssigner(accountId, messages)
            val outcomes = mutableListOf<FolderOutcome>()
            for (folder in plan) {
                val outcome = searchFolder(session, accountId, folder, criteria, assigner)
                outcomes += outcome
                // The connection is gone or the login is refused: the next folder would be too.
                if (outcome is FolderOutcome.Failed && outcome.failure.endsRun) break
            }
            outcomes
        }
        when (leased) {
            is Leased.Ok -> record(leased.value, tally)
            Leased.AuthRequired -> tally.failures += ServerSearchFailure.AUTHENTICATION
            is Leased.Failed -> tally.failures += leased.failure.toSearchFailure()
            Leased.NoAccount -> Unit
        }
    }

    private fun record(outcomes: List<FolderOutcome>, tally: Tally) {
        val failed = outcomes.filterIsInstance<FolderOutcome.Failed>()
        outcomes.filterIsInstance<FolderOutcome.Hits>().forEach {
            tally.rows += it.rows
            tally.added += it.added
        }
        tally.incomplete = tally.incomplete || failed.isNotEmpty() ||
            outcomes.any { it == FolderOutcome.Skipped }
        // Every folder of the account failed: the account counts as failed as a whole.
        if (failed.size == outcomes.size) tally.failures += failed.first().failure.toSearchFailure()
    }

    // Each failure leaves early; guard clauses keep the normal path flat.
    @Suppress("ReturnCount")
    private suspend fun searchFolder(
        session: MailSession,
        accountId: Long,
        folder: FolderEntity,
        criteria: MailSearchCriteria,
        assigner: ThreadAssigner
    ): FolderOutcome {
        val status = session.folderStatus(folder.path).valueOr {
            return FolderOutcome.Failed(it)
        }
        // The numbers this folder was synced with mean other messages now: let a sync reset it.
        if (folder.uidValidity != null && folder.uidValidity != status.uidValidity) {
            return FolderOutcome.Skipped
        }
        val uids = session.search(folder.path, criteria).valueOr { return FolderOutcome.Failed(it) }
        if (uids.isEmpty()) return FolderOutcome.Hits(emptyList(), 0)
        val stored = messages.rowsByUids(accountId, folder.path, uids).map { it.uid }.toSet()
        val missing = uids.filter { it !in stored }.toSet()
        val added = if (missing.isEmpty()) {
            0
        } else {
            store(session, accountId, folder, status, missing, criteria, assigner).valueOr {
                return FolderOutcome.Failed(it)
            }
        }
        val rows = messages.rowsByUids(accountId, folder.path, uids)
            .filter { !criteria.hasAttachment || it.hasAttachments }
        return FolderOutcome.Hits(rows, added)
    }

    /** Fetches the headers of [missing] and inserts them as messages of [folder]. */
    // What one folder's search works with is passed through once; failures leave early.
    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun store(
        session: MailSession,
        accountId: Long,
        folder: FolderEntity,
        status: FolderStatus,
        missing: Set<Long>,
        criteria: MailSearchCriteria,
        assigner: ThreadAssigner
    ): MailResult<Int> {
        val fetched = when (val result = session.fetchHeadersByUid(folder.path, missing)) {
            is MailResult.Success -> result.value
            is MailResult.Failure -> return result
        }
        val headers = fetched.filter {
            !it.flags.deleted && (!criteria.hasAttachment || it.hasAttachments)
        }
        if (headers.isEmpty()) return MailResult.Success(0)
        val now = clock.instant()
        val threads = assigner.assign(
            headers.map {
                it.toThreadMessage(accountId, folder.path, now)
            }
        )
        messages.insertNew(
            headers.map {
                val thread = threads.getValue(MessageRef(accountId, folder.path, it.uid))
                it.toEntity(accountId, folder.path, thread, now)
            }
        )
        // Only the validity, so that a later sync can tell the folder was renumbered.
        if (folder.uidValidity == null) {
            folders.setSyncState(accountId, folder.path, status.uidValidity, null, null)
        }
        return MailResult.Success(headers.size)
    }

    private val MailResult.Failure.endsRun: Boolean
        get() = this == MailResult.NetworkUnavailable || this == MailResult.Timeout ||
            this == MailResult.AuthenticationFailed || this == MailResult.CertificateRejected

    private fun MailResult.Failure.toSearchFailure(): ServerSearchFailure = when {
        this == MailResult.AuthenticationFailed -> ServerSearchFailure.AUTHENTICATION
        toProblem() == SyncProblem.NETWORK -> ServerSearchFailure.OFFLINE
        toProblem() == SyncProblem.TIMEOUT -> ServerSearchFailure.TIMEOUT
        else -> ServerSearchFailure.SERVER
    }

    private companion object {
        /** The most hits handed to the screen; more would not fit a query's variables anyway. */
        const val MAX_HITS = 500
    }
}
