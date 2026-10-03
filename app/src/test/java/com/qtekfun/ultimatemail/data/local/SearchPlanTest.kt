// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteStatement
import com.qtekfun.ultimatemail.data.local.dao.SearchSql
import com.qtekfun.ultimatemail.domain.search.SearchListing
import com.qtekfun.ultimatemail.domain.search.SearchQueryParser
import com.qtekfun.ultimatemail.domain.search.SearchScope
import java.time.ZoneOffset
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
 * Search over 50,000 messages (the reference volume of SPEC section 6). It does not time
 * anything, which would flake on a busy machine: it checks what makes search fast, which is how
 * SQLite plans the query (the full-text index finds the candidates, the primary key fetches
 * them, the ordering index serves a folder) and that every query is bounded by its limit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPlanTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var listing: SearchListing
    private var accountId = 0L

    @BeforeAll
    fun fill() = runTest {
        db = inMemoryDatabase()
        listing = SearchListing(db)
        accountId = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(accountId), folder(accountId, "Sent")))
        (1..VOLUME).chunked(CHUNK).forEach { chunk ->
            db.messageDao().upsert(
                chunk.map { n ->
                    message(
                        accountId,
                        n.toLong(),
                        folderPath = if (n % 10 == 0) "Sent" else "INBOX",
                        subject = "Message $n about topic${n % 500}",
                        senderName = "Sender ${n % 97}",
                        senderAddress = "sender${n % 97}@example.test",
                        sentAt = n * 60_000L,
                        seen = n % 3 == 0,
                        // A rare word that is only in some cached bodies.
                        bodyText = if (n % RARE == 0) "the unicorn arrived on $n" else null
                    )
                }
            )
        }
    }

    @AfterAll
    fun close() = db.close()

    private suspend fun plan(text: String, scope: SearchScope, limit: Int): List<String> {
        val statement = SearchSql.build(SearchQueryParser.parse(text), scope, ZoneOffset.UTC, limit)
        return db.useReaderConnection { connection ->
            connection.usePrepared(
                "EXPLAIN QUERY PLAN ${statement.sql}"
            ) { prepared: SQLiteStatement ->
                statement.args.forEachIndexed { index, arg ->
                    if (arg is Long) {
                        prepared.bindLong(index + 1, arg)
                    } else {
                        prepared.bindText(index + 1, arg.toString())
                    }
                }
                buildList {
                    while (prepared.step()) add(prepared.getText(PLAN_DETAIL))
                }
            }
        }
    }

    @Test
    fun `a word is found through the full-text index and its rows fetched by key`() = runTest {
        val plan = plan("unicorn", SearchScope.AllAccounts, 50)

        assertTrue(plan.any { "message_fts VIRTUAL TABLE INDEX" in it }, plan.toString())
        assertTrue(plan.any { "SEARCH m USING INTEGER PRIMARY KEY" in it }, plan.toString())
        assertFalse(plan.any(::scansMessages), "scans every message: $plan")
    }

    @Test
    fun `a prefix and a sender are found through the index too`() = runTest {
        listOf("topic1", "from:sender5 topic2", "subject:message topic3").forEach {
            val plan = plan(it, SearchScope.Account(accountId), 50)

            assertTrue(
                plan.any { line ->
                    "message_fts VIRTUAL TABLE INDEX" in line
                },
                plan.toString()
            )
            assertFalse(plan.any(::scansMessages), "$it scans: $plan")
            assertEquals("SEARCH m USING INTEGER PRIMARY KEY (rowid=?)", plan.first(), "$it: $plan")
        }
    }

    @Test
    fun `a folder without words is served by the ordering index`() = runTest {
        val plan = plan("is:unread", SearchScope.Folder(accountId, "INBOX"), 50)

        assertTrue(plan.any { "index_message_accountId_folderPath_sentAt" in it }, plan.toString())
        assertFalse(plan.any { "TEMP B-TREE" in it }, "sorts everything: $plan")
    }

    @Test
    fun `the limit is part of every statement and a rare word returns few rows`() = runTest {
        val statement = SearchSql.build(
            SearchQueryParser.parse("unicorn"),
            SearchScope.AllAccounts,
            ZoneOffset.UTC,
            50
        )
        assertTrue(statement.sql.endsWith("LIMIT ?"))
        assertEquals(50L, statement.args.last())

        val page = listing.observe(
            SearchQueryParser.parse("unicorn"),
            SearchScope.AllAccounts,
            50,
            ZoneOffset.UTC
        ).first()

        // Only every RARE-th message has the word in its body.
        assertEquals(VOLUME / RARE, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `a common word is cut at the limit, newest first`() = runTest {
        val page = listing.observe(
            SearchQueryParser.parse("message"),
            SearchScope.AllAccounts,
            50,
            ZoneOffset.UTC
        ).first()

        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("Message $VOLUME about topic${VOLUME % 500}", page.items.first().subject)
        assertTrue(page.items.zipWithNext().all { (a, b) -> a.sentAt >= b.sentAt })
    }

    /** A scan of the message table, or a walk through one of its indexes that is not by key. */
    private fun scansMessages(line: String) = Regex("^SCAN m( |$)").containsMatchIn(line)

    private companion object {
        const val VOLUME = 50_000
        const val CHUNK = 5_000
        const val RARE = 2_500

        /** The `detail` column of EXPLAIN QUERY PLAN. */
        const val PLAN_DETAIL = 3
    }
}
