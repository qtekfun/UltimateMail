// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.engine.AccountSessions
import com.qtekfun.ultimatemail.sync.engine.AccountSync
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.AttachmentFileCleaner
import com.qtekfun.ultimatemail.sync.engine.BodyDownloader
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.FakeMailServer
import com.qtekfun.ultimatemail.sync.engine.FolderCatalog
import com.qtekfun.ultimatemail.sync.engine.FolderPuller
import com.qtekfun.ultimatemail.sync.engine.MailOperationExecutor
import com.qtekfun.ultimatemail.sync.engine.PendingReconciler
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import com.qtekfun.ultimatemail.sync.engine.SyncNotices
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * A controllable network between the engine and the [FakeMailServer]: the connection is cut at
 * the n-th server call from now, either before the server saw the call or after it applied it
 * (the answer is lost), and stays down, for connecting too, until [restore].
 */
class FlakyNetwork(private val harness: EngineHarness) {
    private var down = false
    private var calls = 0
    private var cutAt = Int.MAX_VALUE
    private var applyFirst = false
    private var lostNow = false

    init {
        harness.server.failure = { _ ->
            when {
                down -> MailResult.NetworkUnavailable

                ++calls != cutAt -> null

                applyFirst -> {
                    lostNow = true
                    null
                }

                else -> {
                    cut()
                    MailResult.NetworkUnavailable
                }
            }
        }
        harness.server.answerLost = { _ ->
            if (lostNow) {
                lostNow = false
                cut()
                MailResult.NetworkUnavailable
            } else {
                null
            }
        }
    }

    /** The [n]-th server call from now on never reaches the server; the link stays down. */
    fun dropBefore(n: Int) = arm(n, applyFirst = false)

    /** The [n]-th call is applied by the server but its answer never arrives; the link dies. */
    fun dropAfter(n: Int) = arm(n, applyFirst = true)

    private fun arm(n: Int, applyFirst: Boolean) {
        calls = 0
        cutAt = n
        this.applyFirst = applyFirst
    }

    fun cut() {
        down = true
        harness.connector.failure = MailResult.NetworkUnavailable
    }

    fun restore() {
        down = false
        cutAt = Int.MAX_VALUE
        lostNow = false
        harness.connector.failure = null
    }

    /** Whether the link went down (the armed drop was reached). */
    val isDown get() = down
}

/**
 * A second life of the app over the same Room database and the same server: brand-new queue,
 * executor, session pool, body downloader (its in-memory failure counters are gone) and engine,
 * as after the process was killed and started again.
 */
class RestartedApp(harness: EngineHarness, scope: TestScope) {
    val status = SyncStatusStore()
    val notices = SyncNotices()
    private val db = harness.db
    private val sessions = AccountSessions(
        db.accountDao(),
        harness.credentials,
        harness.connector,
        status
    )
    val marker = PendingSyncMarker(db.messageDao(), db.pendingOperationDao())
    private val executor = MailOperationExecutor(
        db.accountDao(),
        db.folderDao(),
        db.messageDao(),
        sessions,
        harness.credentials,
        harness.sender,
        marker,
        status,
        notices
    )
    val queue = OperationQueue(
        db.pendingOperationDao(),
        executor,
        harness.clock,
        StandardTestDispatcher(scope.testScheduler)
    )
    private val bodies = BodyDownloader(
        db.messageDao(),
        com.qtekfun.ultimatemail.sync.engine.BodyStore(db.messageDao(), db.attachmentDao()),
        DownloadAttachment(db.attachmentDao(), db.messageDao(), sessions, harness.storage),
        harness.offlineDownloads,
        status,
        harness.clock
    )
    val engine = SyncEngine(
        db.accountDao(),
        AccountSync(
            db.accountDao(),
            db.folderDao(),
            db.messageDao(),
            sessions,
            FolderCatalog(db.folderDao()),
            FolderPuller(
                db.messageDao(),
                db.folderDao(),
                PendingReconciler(db.messageDao(), db.pendingOperationDao(), notices),
                harness.clock
            ),
            queue,
            AttachmentFileCleaner(db.attachmentDao(), harness.storage),
            bodies,
            harness.clock
        ),
        status,
        harness.clock
    )
}

/** Everything the user could see in Room, without ids, as comparable text. */
data class RoomState(
    val folders: List<String>,
    val messages: List<String>,
    val operations: List<String>
)

suspend fun EngineHarness.roomState(): RoomState {
    val all = folders.all(accountId).sortedBy { it.path }
    val rows = all.flatMap { folder ->
        messages.identities(accountId, folder.path).mapNotNull { messages.getById(it.id) }
    }.map {
        listOf(
            it.folderPath,
            it.uid,
            it.messageId,
            it.subject,
            "seen=${it.seen}",
            "flagged=${it.flagged}",
            "answered=${it.answered}",
            "draft=${it.draft}",
            it.labels,
            it.threadId,
            "text=${it.bodyText}",
            "html=${it.bodyHtml}",
            "pending=${it.pendingSync}"
        ).joinToString("|")
    }.sorted()
    return RoomState(
        folders = all.map {
            "${it.path}|${it.syncEnabled}|${it.uidValidity}|${it.uidNext}|${it.highestModSeq}"
        },
        messages = rows,
        operations = operations.all(accountId).map {
            "${it.type}|${it.folderPath}|${it.uid}|${it.payload}|failed=${it.failed}"
        }
    )
}

/** What the server holds, by Message-ID and flags (UIDs are the server's business). */
fun FakeMailServer.contents(): Map<String, List<String>> =
    folders.values.sortedBy { it.path }.associate { folder ->
        folder.path to folder.messages.values.map { "${it.messageId}|${it.flags}" }.sorted()
    }

/** The server calls of a run, without the connection closing. */
fun FakeMailServer.calls() = log.filter { it != "close" }

/** Gives every queued retry time to come due. */
fun EngineHarness.waitOutBackoff() {
    clock.now = clock.now.plus(Duration.ofHours(1))
}

/** Syncs until it succeeds, waiting out the backoff between tries; returns how many it took. */
suspend fun EngineHarness.syncUntilDone(app: SyncEngine = engine, max: Int = 4): Int {
    repeat(max) { attempt ->
        waitOutBackoff()
        if (app.sync(accountId) is AccountSyncResult.Synced) return attempt + 1
    }
    error("still not synced after $max runs")
}

/** A mailbox for the scenarios: a few messages with bodies in three folders. */
fun FakeMailServer.populate(inbox: Int = 4, archive: Int = 2) {
    folder("INBOX", MailFolderRole.INBOX)
    folder("Archive", MailFolderRole.ARCHIVE)
    folder("Sent", MailFolderRole.SENT)
    repeat(inbox) { deliverWithBody("INBOX", "in-${it + 1}") }
    repeat(archive) { deliverWithBody("Archive", "ar-${it + 1}") }
    deliverWithBody("Sent", "se-1")
}

fun FakeMailServer.deliverWithBody(
    path: String,
    tag: String,
    flags: MessageFlags = MessageFlags(),
    sentAtSeconds: Long = 1_700_000_000
): Long {
    val uid = deliver(
        path,
        subject = "Subject $tag",
        messageId = "<$tag@example.test>",
        flags = flags,
        sentAt = Instant.ofEpochSecond(sentAtSeconds + folder(path).nextUid)
    )
    folder(path).bodies[uid] = MessageBody("text of $tag", "<p>html of $tag</p>", emptyList())
    return uid
}
