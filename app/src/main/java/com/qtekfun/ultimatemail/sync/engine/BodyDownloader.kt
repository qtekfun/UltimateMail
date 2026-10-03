// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.BodyWorkRow
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one sync run may spend on downloading bodies, so a run never hogs the radio or the
 * battery. Whichever limit is reached first ends the phase; the rest continues on the next run.
 */
data class BodyBudget(val maxMessages: Int, val maxBytes: Long, val maxDuration: Duration) {
    companion object {
        /** 500 messages, 25 MB of text and HTML, or 2 minutes per run. */
        val DEFAULT = BodyBudget(
            maxMessages = 500,
            maxBytes = 25L * 1024 * 1024,
            maxDuration = Duration.ofMinutes(2)
        )
    }
}

internal sealed interface BodyOutcome {
    /** The phase is over: everything was fetched, or the budget ran out (the rest waits). */
    data object Finished : BodyOutcome

    /** The connection or the login is gone; the failure ends the whole sync run. */
    data class Stopped(val failure: MailResult.Failure) : BodyOutcome
}

/**
 * Downloads the full bodies (text and HTML) of the messages inside the offline window during
 * sync, so reading works offline (RF-10). The work list is Room itself: the messages of synced
 * folders, inside the window, without a body, newest first. A run that is cut short loses
 * nothing, because the next one asks Room again and a stored body is never fetched twice.
 *
 * Attachments stay on the server until tapped, except the small images the HTML shows inline
 * (`cid:`), which come with the body so the HTML renders offline. A message above
 * [MAX_MESSAGE_BYTES] is left to be fetched when it is opened. A message that fails does not hold
 * up the others; after [MAX_FAILURES] failures it is skipped until the app restarts.
 */
@Singleton
internal class BodyDownloader @Inject constructor(
    private val messages: MessageDao,
    private val store: BodyStore,
    private val inline: DownloadAttachment,
    private val setting: OfflineDownloads,
    private val status: SyncStatusStore,
    private val clock: Clock
) {
    /** Failed fetches per message id, in memory only: a counter column is not worth a schema. */
    private val failures = ConcurrentHashMap<Long, Int>()

    /** What one run of the phase has done so far, and where in the work list it stands. */
    private class Pass(
        val account: AccountEntity,
        val sinceMillis: Long,
        val budget: BodyBudget,
        val started: Instant,
        var done: Int,
        val total: Int
    ) {
        var attempts = 0
        var bytes = 0L
        var beforeSentAt = Long.MAX_VALUE
        var beforeId = Long.MAX_VALUE
    }

    suspend fun run(
        session: MailSession,
        account: AccountEntity,
        budget: BodyBudget = BodyBudget.DEFAULT
    ): BodyOutcome {
        val pass = plan(account, budget) ?: return BodyOutcome.Finished
        status.set(account.id, AccountSyncState.DownloadingBodies(pass.done, pass.total))
        var outcome: BodyOutcome? = null
        while (outcome == null) {
            val batch = messages.bodyWork(
                account.id,
                pass.sinceMillis,
                MAX_MESSAGE_BYTES,
                pass.beforeSentAt,
                pass.beforeId,
                BATCH
            )
            outcome = if (batch.isEmpty()) BodyOutcome.Finished else process(session, pass, batch)
        }
        return outcome
    }

    /** The pass to run, or null when the switch is off or every body is already there. */
    private suspend fun plan(account: AccountEntity, budget: BodyBudget): Pass? {
        if (!setting.isEnabled(account.id)) return null
        val since = account.offlineWindowDays
            ?.let { clock.instant().minus(Duration.ofDays(it.toLong())).toEpochMilli() }
            ?: Long.MIN_VALUE
        val total = messages.countBodyCandidates(account.id, since, MAX_MESSAGE_BYTES)
        val missing = messages.countBodiesMissing(account.id, since, MAX_MESSAGE_BYTES)
        return if (missing == 0) {
            null
        } else {
            Pass(account, since, budget, clock.instant(), total - missing, total)
        }
    }

    /** Works through [batch]; returns the outcome when the run ends, null to ask for more. */
    private suspend fun process(
        session: MailSession,
        pass: Pass,
        batch: List<BodyWorkRow>
    ): BodyOutcome? {
        for (row in batch) {
            val stop = if (spent(pass)) {
                BodyOutcome.Finished
            } else {
                pass.beforeSentAt = row.sentAt.toEpochMilli()
                pass.beforeId = row.id
                fetch(session, pass, row)
            }
            if (stop != null) return stop
        }
        return null
    }

    private fun spent(pass: Pass) = pass.attempts >= pass.budget.maxMessages ||
        pass.bytes >= pass.budget.maxBytes ||
        Duration.between(pass.started, clock.instant()) >= pass.budget.maxDuration

    /** Fetches and stores one body; returns an outcome only when the whole run must stop. */
    private suspend fun fetch(session: MailSession, pass: Pass, row: BodyWorkRow): BodyOutcome? {
        if ((failures[row.id] ?: 0) >= MAX_FAILURES) return null
        pass.attempts++
        return when (val fetched = session.fetchBody(row.folderPath, row.uid)) {
            is MailResult.Success -> {
                pass.bytes += save(row, fetched.value)
                pass.done++
                status.set(
                    pass.account.id,
                    AccountSyncState.DownloadingBodies(pass.done, pass.total)
                )
                null
            }

            // Gone from the server: the next pull's reconcile removes the row.
            MailResult.NotFound -> null

            is MailResult.Failure -> if (fetched.endsRun) {
                BodyOutcome.Stopped(fetched)
            } else {
                recordFailure(row.id)
                null
            }
        }
    }

    /** Stores the body and its inline images; returns the bytes of text it brought. */
    private suspend fun save(row: BodyWorkRow, body: MessageBody): Long {
        val message = messages.getById(row.id) ?: return 0
        store.save(message, body)
        inline.fetchInline(row.id)
        return (body.text?.length ?: 0) + (body.html?.length ?: 0).toLong()
    }

    private fun recordFailure(id: Long) {
        if (failures.size >= MAX_TRACKED) failures.clear()
        failures.merge(id, 1, Int::plus)
    }

    companion object {
        /**
         * Messages bigger than this (the size the server reports, attachments included) are not
         * downloaded during sync; they are fetched when opened. 10 MB.
         */
        const val MAX_MESSAGE_BYTES = 10L * 1024 * 1024

        /** Messages read from Room at a time. */
        const val BATCH = 20

        /** After this many failed fetches a message is skipped until the app restarts. */
        const val MAX_FAILURES = 3

        private const val MAX_TRACKED = 5_000
    }
}

/** The connection or the login is gone: the other calls of the run would fail the same way. */
internal val MailResult.Failure.endsRun: Boolean
    get() = this == MailResult.NetworkUnavailable || this == MailResult.Timeout ||
        this == MailResult.AuthenticationFailed || this == MailResult.CertificateRejected
