// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteStatement
import com.qtekfun.ultimatemail.data.local.dao.BODY_WORK_SQL
import com.qtekfun.ultimatemail.data.local.dao.CONVERSATIONS_OF_FOLDER_SQL
import com.qtekfun.ultimatemail.data.local.dao.DRAFTS_BY_STATE_SQL
import com.qtekfun.ultimatemail.data.local.dao.OUTBOX_SQL
import com.qtekfun.ultimatemail.data.local.dao.THREAD_INDEX
import com.qtekfun.ultimatemail.data.local.dao.THREAD_SQL
import com.qtekfun.ultimatemail.data.local.dao.UNIFIED_INBOX_SQL
import com.qtekfun.ultimatemail.data.local.dao.UNREAD_PER_FOLDER_SQL
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * How SQLite plans the queries behind the screens, over the reference volume of SPEC section 6
 * (50,000 messages, two accounts). Nothing is timed, which would flake on a busy machine: the
 * plan is what decides whether a list stays fast as the mailbox grows, so these tests fail when
 * a query (or an index it relies on) changes in a way that walks every message.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPlanTest {
    private lateinit var db: UltimateMailDatabase
    private var first = 0L
    private var second = 0L

    @BeforeAll
    fun fill() = runTest {
        db = inMemoryDatabase()
        first = db.accountDao().insert(account("ana@example.test"))
        second = db.accountDao().insert(account("bea@example.test"))
        listOf(first, second).forEach { id ->
            db.folderDao().upsert(
                listOf(
                    folder(id, "INBOX", FolderRole.INBOX),
                    folder(id, "Sent", FolderRole.SENT),
                    folder(id, "Drafts", FolderRole.DRAFTS),
                    folder(id, "Archive", FolderRole.ARCHIVE)
                )
            )
        }
        (1..VOLUME).chunked(CHUNK).forEach { chunk ->
            db.messageDao().upsert(
                chunk.map { n ->
                    message(
                        if (n % 2 == 0) first else second,
                        n.toLong(),
                        folderPath = FOLDERS[n % FOLDERS.size],
                        threadId = "thread${n / THREAD_SIZE}",
                        seen = n % 3 == 0,
                        sentAt = n * 60_000L,
                        bodyText = if (n % 4 == 0) "body" else null
                    )
                }
            )
        }
        queueSomeWork()
    }

    private suspend fun queueSomeWork() {
        val now = Instant.ofEpochMilli(NOW)
        repeat(PENDING) { n ->
            db.pendingOperationDao().enqueue(
                PendingOperationEntity(
                    accountId = first,
                    type = if (n % 5 == 0) OperationType.SEND else OperationType.MOVE,
                    folderPath = if (n % 5 == 0) "" else "INBOX",
                    uid = (n * 2L) + 2,
                    payload = "Archive",
                    createdAt = now
                )
            )
        }
        repeat(PENDING) { n ->
            db.draftDao().insert(
                DraftEntity(
                    key = "draft$n",
                    accountId = first,
                    kind = DraftKind.NEW,
                    state = if (n % 2 == 0) DraftState.EDITING else DraftState.OUTBOX,
                    createdAt = now,
                    updatedAt = Instant.ofEpochMilli(NOW + n)
                )
            )
        }
    }

    @AfterAll
    fun close() = db.close()

    /** The plan of [sql] (named `:parameters` become `?`), one detail line per step. */
    private suspend fun plan(sql: String): List<String> {
        val positional = sql.replace(Regex(":[A-Za-z]\\w*"), "?")
        val parameters = positional.count { it == '?' }
        return db.useReaderConnection { connection ->
            connection.usePrepared("EXPLAIN QUERY PLAN $positional") { prepared: SQLiteStatement ->
                repeat(parameters) { prepared.bindLong(it + 1, 1L) }
                buildList {
                    while (prepared.step()) add(prepared.getText(PLAN_DETAIL))
                }
            }
        }
    }

    /** A walk over a whole table of messages: neither a search by key nor by an index prefix. */
    private fun scansMessages(line: String) =
        Regex("^SCAN (m|t|message)( |$)").containsMatchIn(line)

    private fun assertNoMessageScan(plan: List<String>) =
        assertFalse(plan.any(::scansMessages), "walks every message: $plan")

    /**
     * The lookup of a conversation's messages must go by the thread index: by the (folder, date)
     * one it walks the folder down from its newest message, so a list costs n squared.
     */
    private fun assertThreadLookupsByThreadIndex(plan: List<String>) {
        val lookups = plan.filter { it.startsWith("SEARCH t USING") }
        assertTrue(lookups.isNotEmpty(), plan.toString())
        assertTrue(
            lookups.all { THREAD_INDEX in it },
            "a conversation is looked up by something else than its thread: $plan"
        )
    }

    @Test
    fun `the conversations of a folder come from its date index and each row costs its thread`() =
        runTest {
            val plan = plan(CONVERSATIONS_OF_FOLDER_SQL)

            assertEquals(
                "SEARCH m USING INDEX index_message_accountId_folderPath_sentAt " +
                    "(accountId=? AND folderPath=?)",
                plan.first()
            )
            assertNoMessageScan(plan)
            assertThreadLookupsByThreadIndex(plan)
        }

    @Test
    fun `the unified inbox reads the messages of the inbox folders by index`() = runTest {
        val plan = plan(UNIFIED_INBOX_SQL)

        assertTrue(plan.any { "SEARCH m USING INDEX index_message_accountId_folderPath" in it })
        assertNoMessageScan(plan)
        assertThreadLookupsByThreadIndex(plan)
    }

    @Test
    fun `queued operations that hide a message are found by message, not by scanning the queue`() =
        runTest {
            val plan = plan(CONVERSATIONS_OF_FOLDER_SQL)

            val queue = plan.filter { it.startsWith("SEARCH p USING") || it.startsWith("SEARCH a") }
            assertTrue(queue.isNotEmpty())
            assertTrue(queue.all { QUEUE_INDEX in it }, "$queue")
            assertFalse(plan.any { it.startsWith("SCAN p") || it.startsWith("SCAN a") }, "$plan")
        }

    @Test
    fun `unread counts go through the account index and never scan the table`() = runTest {
        val plan = plan(UNREAD_PER_FOLDER_SQL)

        assertEquals(1, plan.size, plan.toString())
        assertTrue(
            "SEARCH message USING INDEX" in plan.single() && "(accountId=?)" in plan.single()
        )
    }

    @Test
    fun `a conversation is read through its thread index`() = runTest {
        val plan = plan(THREAD_SQL)

        assertEquals(
            listOf(
                "SEARCH message USING INDEX $THREAD_INDEX " +
                    "(accountId=? AND folderPath=? AND threadId=?)"
            ),
            plan.filterNot { "TEMP B-TREE" in it }
        )
    }

    @Test
    fun `the body download work list is bounded by an index of the account's messages`() = runTest {
        val plan = plan(BODY_WORK_SQL)

        assertTrue(plan.any { "SEARCH m USING INDEX" in it && "accountId=?" in it }, "$plan")
        assertNoMessageScan(plan)
    }

    @Test
    fun `the outbox and the drafts of an account are found by index`() = runTest {
        val outbox = plan(OUTBOX_SQL)
        val drafts = plan(DRAFTS_BY_STATE_SQL)

        assertTrue(outbox.first().startsWith("SEARCH pending_operation USING INDEX"), "$outbox")
        assertEquals(
            "SEARCH draft USING INDEX index_draft_accountId_state_updatedAt " +
                "(accountId=? AND state=?)",
            drafts.single()
        )
    }

    @Test
    fun `the list still answers right with the index hint, newest conversation first`() = runTest {
        val conversations = db.conversationDao().observeConversations(first, "INBOX", LIST).first()
        val thread = conversations.first().latest.run {
            db.messageDao().thread(accountId, folderPath, threadId)
        }

        assertEquals(LIST, conversations.size)
        assertEquals(LIST, conversations.map { it.latest.threadId }.toSet().size)
        assertTrue(conversations.zipWithNext().all { (a, b) -> a.latest.sentAt >= b.latest.sentAt })
        assertEquals(conversations.first().messageCount, thread.size)
        assertTrue(thread.zipWithNext().all { (a, b) -> a.sentAt <= b.sentAt })
    }

    private companion object {
        const val VOLUME = 50_000
        const val CHUNK = 5_000
        const val THREAD_SIZE = 4
        const val PENDING = 40
        const val LIST = 100
        const val NOW = 1_700_000_000_000L
        const val QUEUE_INDEX = "index_pending_operation_accountId_folderPath_uid"
        val FOLDERS = listOf("INBOX", "Sent", "Archive", "INBOX")

        /** The `detail` column of EXPLAIN QUERY PLAN. */
        const val PLAN_DETAIL = 3
    }
}
