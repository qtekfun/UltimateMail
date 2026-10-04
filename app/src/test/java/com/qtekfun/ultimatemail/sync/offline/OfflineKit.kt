// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.offline

import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.engine.AccountSessions
import com.qtekfun.ultimatemail.sync.engine.AccountSync
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.AttachmentFileCleaner
import com.qtekfun.ultimatemail.sync.engine.BodyDownloader
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.FakeMailServer
import com.qtekfun.ultimatemail.sync.engine.FakeSyncDepthLog
import com.qtekfun.ultimatemail.sync.engine.FolderCatalog
import com.qtekfun.ultimatemail.sync.engine.FolderPuller
import com.qtekfun.ultimatemail.sync.engine.MailOperationExecutor
import com.qtekfun.ultimatemail.sync.engine.PendingReconciler
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import com.qtekfun.ultimatemail.sync.engine.SyncNotices
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import com.qtekfun.ultimatemail.sync.queue.NewOperation
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull

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
        notices,
        harness.outbox
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
            FakeSyncDepthLog(),
            status,
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
    val stored = all.flatMap { folder ->
        messages.identities(accountId, folder.path).mapNotNull { messages.getById(it.id) }
    }
    // A thread id is an opaque key whose spelling depends on where a message was first seen:
    // compare which messages share a conversation, not the key itself.
    val groups = stored.sortedBy { "${it.messageId}${it.folderPath}" }.map { it.threadId }
        .distinct()
    val rows = stored.map {
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
            "thread=${groups.indexOf(it.threadId)}",
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

class QuietScheduler : SyncScheduler {
    override fun startPeriodic() = Unit

    override fun requestSync(accountId: Long?, userInitiated: Boolean) = Unit

    override fun stop() = Unit
}

suspend fun EngineHarness.id(folder: String, uid: Long) = messages.get(accountId, folder, uid)!!.id

/** The user acts offline: read, star, move, delete and read in another folder. */
suspend fun EngineHarness.userActs() {
    val actions = ConversationActions(messages, queue, marker, QuietScheduler())
    actions.markRead(id("INBOX", 1))
    actions.setStarred(id("INBOX", 2), true)
    actions.move(listOf(id("INBOX", 3)), "Archive")
    queue.enqueue(NewOperation(accountId, OperationType.DELETE, "INBOX", 4, ""))
    actions.markRead(id("Archive", 1))
    undoWindowPasses()
}

/** The seconds in which the user could still undo a move are over, so the move may be sent. */
fun EngineHarness.undoWindowPasses() {
    clock.now = clock.now.plus(NewOperation.UNDO_HOLD)
}

/** The process dies right here: nothing after this call runs, no cleanup of the app's own. */
class Killed : Error("process killed")

/** Kills the app at the n-th server call from now, before or after the server applied it. */
class Killer(harness: EngineHarness) {
    private var calls = 0
    private var at = Int.MAX_VALUE
    private var afterApply = false
    private var armed = false

    init {
        harness.server.failure = { _ ->
            if (++calls == at && !afterApply) throw Killed()
            if (calls == at) armed = true
            null
        }
        harness.server.answerLost = { _ ->
            if (armed) throw Killed()
            null
        }
    }

    fun killBefore(n: Int) = arm(n, false)

    fun killAfter(n: Int) = arm(n, true)

    private fun arm(n: Int, after: Boolean) {
        calls = 0
        at = n
        afterApply = after
        armed = false
    }

    fun disarm() = arm(Int.MAX_VALUE, false)

    /** Runs [block] and says whether the process died in it. */
    suspend fun died(block: suspend () -> Unit): Boolean = try {
        block()
        false
    } catch (@Suppress("SwallowedException") killed: Killed) {
        true
    }
}

suspend fun EngineHarness.collectNotices(): List<SyncNotice> {
    val seen = mutableListOf<SyncNotice>()
    withTimeoutOrNull(1) { notices.notices.collect { seen += it } }
    return seen
}

/** The server's copy of a message by Message-ID in [folder], or null if it is not there. */
fun FakeMailServer.copy(folder: String, tag: String) =
    folder(folder).messages.values.firstOrNull { it.messageId == "<$tag@example.test>" }

/** How many copies of the message the server has in all its folders. */
fun FakeMailServer.copies(tag: String) =
    folders.values.sumOf { f -> f.messages.values.count { it.messageId == "<$tag@example.test>" } }
