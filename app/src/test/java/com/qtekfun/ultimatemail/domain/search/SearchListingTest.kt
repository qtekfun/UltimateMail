// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The local search end to end: text to SQL to Room (the real SQLite, FTS4 included). */
class SearchListingTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var listing: SearchListing
    private var ana = 0L
    private var bea = 0L

    @BeforeEach
    fun setUp() = runTest {
        db = inMemoryDatabase()
        listing = SearchListing(db)
        ana = db.accountDao().insert(account("ana@example.test"))
        bea = db.accountDao().insert(account("bea@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(ana, "INBOX", FolderRole.INBOX),
                folder(ana, "Sent", FolderRole.SENT),
                folder(ana, "Trash", FolderRole.TRASH),
                folder(ana, "Spam", FolderRole.JUNK),
                folder(ana, "Work/Invoices", FolderRole.OTHER),
                folder(bea, "INBOX", FolderRole.INBOX)
            )
        )
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(vararg messages: MessageEntity) =
        db.messageDao().upsert(messages.toList())

    private suspend fun uids(
        text: String,
        scope: SearchScope = SearchScope.AllAccounts,
        filters: SearchFilters = SearchFilters.NONE,
        limit: Int = 50
    ): List<Long> {
        val query = filters.applyTo(SearchQueryParser.parse(text), LocalDate.of(2026, 10, 3))
        return listing.observe(query, scope, limit, ZoneOffset.UTC).first().items
            .map { it.latestMessageId }
            .map { db.messageDao().getById(it)!!.uid }
    }

    @Test
    fun `finds words in the subject, the sender and a cached body`() = runTest {
        insert(
            message(ana, 1, subject = "Invoice March"),
            message(ana, 2, subject = "Hello", senderName = "Invoice Bot"),
            message(ana, 3, subject = "Hello", bodyText = "please find the invoice attached"),
            message(ana, 4, subject = "Other")
        )

        assertEquals(listOf(3L, 2L, 1L), uids("invoice"))
    }

    @Test
    fun `ignores case and accents in both directions`() = runTest {
        insert(
            message(ana, 1, subject = "Reunión del ñandú"),
            message(ana, 2, subject = "REUNION general"),
            message(ana, 3, subject = "Cafe con leche")
        )

        assertEquals(listOf(2L, 1L), uids("reunion"))
        assertEquals(listOf(2L, 1L), uids("REUNIÓN"))
        assertEquals(listOf(1L), uids("NANDU"))
        assertEquals(listOf(1L), uids("ñandú"))
        assertEquals(listOf(3L), uids("café"))
    }

    @Test
    fun `all the words must be found, in any order`() = runTest {
        insert(
            message(ana, 1, subject = "budget march"),
            message(ana, 2, subject = "budget april"),
            message(ana, 3, subject = "march plans", bodyText = "budget inside")
        )

        assertEquals(listOf(3L, 1L), uids("march budget"))
    }

    @Test
    fun `the last word matches as a prefix while it is being typed`() = runTest {
        insert(
            message(ana, 1, subject = "Invoices"),
            message(ana, 2, subject = "Invitation"),
            message(ana, 3, subject = "Other")
        )

        assertEquals(listOf(2L, 1L), uids("inv"))
        assertEquals(emptyList<Long>(), uids("inv "))
        assertEquals(listOf(1L), uids("invoices "))
    }

    @Test
    fun `a phrase needs its words together`() = runTest {
        insert(
            message(ana, 1, subject = "lunch plans for friday"),
            message(ana, 2, subject = "plans for lunch")
        )

        assertEquals(listOf(1L), uids("\"lunch plans\""))
        assertEquals(listOf(2L, 1L), uids("lunch plans"))
    }

    @Test
    fun `excluded words remove hits`() = runTest {
        insert(
            message(ana, 1, subject = "lunch pizza"),
            message(ana, 2, subject = "lunch salad"),
            message(ana, 3, subject = "dinner pizza")
        )

        assertEquals(listOf(2L), uids("lunch -pizza "))
        assertEquals(listOf(2L), uids("-pizza -dinner "))
    }

    @Test
    fun `from looks at the name and at the address`() = runTest {
        insert(
            message(ana, 1, senderName = "Ana Pérez", senderAddress = "aperez@example.test"),
            message(ana, 2, senderName = "Bob", senderAddress = "ana.lopez@example.test"),
            message(
                ana,
                3,
                senderName = "Carla",
                senderAddress = "carla@example.test",
                subject = "ana"
            )
        )

        assertEquals(listOf(2L, 1L), uids("from:ana"))
        assertEquals(listOf(1L), uids("from:perez"))
        assertEquals(listOf(1L), uids("from:\"ana perez\" "))
        assertEquals(listOf(3L), uids("ana from:carla "))
    }

    @Test
    fun `subject only looks at the subject`() = runTest {
        insert(
            message(ana, 1, subject = "report"),
            message(ana, 2, subject = "other", bodyText = "report"),
            message(ana, 3, subject = "other", senderName = "Report Bot")
        )

        assertEquals(listOf(1L), uids("subject:report "))
        assertEquals(listOf(3L, 2L, 1L), uids("report "))
    }

    @Test
    fun `to matches the To and Cc addresses`() = runTest {
        insert(
            message(ana, 1).copy(toAddresses = listOf("bob@example.test", "eve@example.test")),
            message(ana, 2).copy(ccAddresses = listOf("bob@example.test")),
            message(ana, 3).copy(toAddresses = listOf("carla@example.test"))
        )

        assertEquals(listOf(2L, 1L), uids("to:bob"))
        assertEquals(listOf(3L), uids("to:carla@example.test "))
        assertEquals(emptyList<Long>(), uids("to:zed "))
    }

    @Test
    fun `to treats percent and underscore as plain characters`() = runTest {
        insert(
            message(ana, 1).copy(toAddresses = listOf("a_b@example.test")),
            message(ana, 2).copy(toAddresses = listOf("axb@example.test"))
        )

        assertEquals(listOf(1L), uids("to:a_b "))
        assertEquals(emptyList<Long>(), uids("to:a%b "))
    }

    @Test
    fun `label and in match the folder by role or name and Gmail labels`() = runTest {
        insert(
            message(ana, 1, folderPath = "INBOX"),
            message(ana, 2, folderPath = "Sent"),
            message(ana, 3, folderPath = "Work/Invoices"),
            message(ana, 4, folderPath = "INBOX", labels = listOf("\\Inbox", "Receipts")),
            message(ana, 5, folderPath = "Trash")
        )

        assertEquals(listOf(4L, 1L), uids("in:inbox "))
        assertEquals(listOf(2L), uids("in:sent "))
        assertEquals(listOf(3L), uids("label:invoices "))
        assertEquals(listOf(4L), uids("label:receipts "))
        assertEquals(listOf(5L), uids("in:trash "))
        assertEquals(listOf(4L), uids("label:\\Inbox label:receipts "))
    }

    @Test
    fun `flags and attachments narrow the hits`() = runTest {
        insert(
            message(ana, 1, seen = false, flagged = true, hasAttachments = true),
            message(ana, 2, seen = true, flagged = true),
            message(ana, 3, seen = false, hasAttachments = true),
            message(ana, 4, seen = true)
        )

        assertEquals(listOf(3L, 1L), uids("is:unread "))
        assertEquals(listOf(4L, 2L), uids("is:read "))
        assertEquals(listOf(2L, 1L), uids("is:starred "))
        assertEquals(listOf(3L, 1L), uids("has:attachment "))
        assertEquals(listOf(1L), uids("is:unread is:starred has:attachment "))
    }

    @Test
    fun `chips work the same as operators`() = runTest {
        insert(
            message(ana, 1, seen = false, flagged = true, hasAttachments = true),
            message(ana, 2, seen = true)
        )

        assertEquals(listOf(1L), uids("", filters = SearchFilters(unread = true)))
        assertEquals(listOf(1L), uids("", filters = SearchFilters(starred = true)))
        assertEquals(listOf(1L), uids("", filters = SearchFilters(withAttachments = true)))
    }

    private fun day(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `after includes its day and before excludes its day`() = runTest {
        insert(
            message(ana, 1, sentAt = day(2026, 3, 1) - 1),
            message(ana, 2, sentAt = day(2026, 3, 1)),
            message(ana, 3, sentAt = day(2026, 3, 31) + 86_399_999),
            message(ana, 4, sentAt = day(2026, 4, 1))
        )

        assertEquals(listOf(4L, 3L, 2L), uids("after:2026-03-01 "))
        assertEquals(listOf(3L, 2L, 1L), uids("before:2026/04/01 "))
        assertEquals(listOf(3L, 2L), uids("after:2026-03-01 before:2026-04-01 "))
    }

    @Test
    fun `days are the days of the user's time zone`() = runTest {
        val midnightUtc = day(2026, 3, 1)
        insert(message(ana, 1, sentAt = midnightUtc))
        val query = SearchQueryParser.parse("after:2026-03-01 ")

        val inUtc = listing.observe(query, SearchScope.AllAccounts, 10, ZoneOffset.UTC)
        val inTokyo = listing.observe(query, SearchScope.AllAccounts, 10, ZoneId.of("Asia/Tokyo"))

        // 00:00 UTC is 09:00 on the 1st in Tokyo, still after its midnight; in New York it is
        // the evening of the 28th, before the day started.
        assertEquals(1, inUtc.first().items.size)
        assertEquals(1, inTokyo.first().items.size)
        val inNewYork = listing.observe(
            query,
            SearchScope.AllAccounts,
            10,
            ZoneId.of("America/New_York")
        )
        assertEquals(0, inNewYork.first().items.size)
    }

    @Test
    fun `the date chip with the last 30 days`() = runTest {
        insert(
            message(ana, 1, sentAt = day(2026, 9, 1)),
            message(ana, 2, sentAt = day(2026, 9, 20))
        )

        assertEquals(
            listOf(2L),
            uids("", filters = SearchFilters(date = DateFilter.Last30Days))
        )
    }

    @Test
    fun `scope limits the search to a folder, an account or everything`() = runTest {
        insert(
            message(ana, 1, subject = "alpha", folderPath = "INBOX"),
            message(ana, 2, subject = "alpha", folderPath = "Sent"),
            message(bea, 3, subject = "alpha", folderPath = "INBOX")
        )

        assertEquals(listOf(3L, 2L, 1L), uids("alpha"))
        assertEquals(listOf(2L, 1L), uids("alpha", SearchScope.Account(ana)))
        assertEquals(listOf(1L), uids("alpha", SearchScope.Folder(ana, "INBOX")))
        assertEquals(listOf(3L), uids("alpha", SearchScope.Folder(bea, "INBOX")))
    }

    @Test
    fun `trash and spam only show when asked for`() = runTest {
        insert(
            message(ana, 1, subject = "alpha", folderPath = "INBOX"),
            message(ana, 2, subject = "alpha", folderPath = "Trash"),
            message(ana, 3, subject = "alpha", folderPath = "Spam")
        )

        assertEquals(listOf(1L), uids("alpha"))
        assertEquals(listOf(1L), uids("alpha", SearchScope.Account(ana)))
        assertEquals(listOf(2L), uids("alpha", SearchScope.Folder(ana, "Trash")))
        assertEquals(listOf(2L), uids("alpha in:trash "))
        assertEquals(listOf(3L), uids("alpha in:spam "))
    }

    @Test
    fun `results are newest first and report when there may be more`() = runTest {
        insert(*(1L..5L).map { message(ana, it, subject = "alpha $it") }.toTypedArray())
        val query = SearchQueryParser.parse("alpha")

        val page = listing.observe(query, SearchScope.AllAccounts, 3, ZoneOffset.UTC).first()
        val all = listing.observe(query, SearchScope.AllAccounts, 10, ZoneOffset.UTC).first()

        assertEquals(listOf("alpha 5", "alpha 4", "alpha 3"), page.items.map { it.subject })
        assertTrue(page.hasMore)
        assertEquals(5, all.items.size)
        assertFalse(all.hasMore)
    }

    @Test
    fun `a conversation is one row with its counters`() = runTest {
        insert(
            message(ana, 1, threadId = "t", subject = "alpha", seen = true),
            message(ana, 2, threadId = "t", subject = "alpha", seen = false),
            message(ana, 3, threadId = "other", subject = "alpha")
        )

        val items = listing.observe(
            SearchQueryParser.parse("alpha"),
            SearchScope.AllAccounts,
            10,
            ZoneOffset.UTC
        )
            .first().items

        assertEquals(2, items.size)
        val thread = items.single { it.threadId == "t" }
        assertEquals(2, thread.messageCount)
        assertEquals(1, thread.unreadCount)
        assertEquals(2L, db.messageDao().getById(thread.latestMessageId)!!.uid)
    }

    @Test
    fun `a Gmail message in several folders shows once`() = runTest {
        insert(
            message(ana, 1, subject = "alpha", folderPath = "INBOX", sentAt = 5_000)
                .copy(gmailMessageId = 77, threadId = "gmail:$ana:9"),
            message(ana, 2, subject = "alpha", folderPath = "Work/Invoices", sentAt = 5_000)
                .copy(gmailMessageId = 77, threadId = "gmail:$ana:9")
        )

        assertEquals(1, uids("alpha").size)
    }

    @Test
    fun `a message waiting to be moved or deleted is not a hit`() = runTest {
        insert(
            message(ana, 1, subject = "alpha"),
            message(ana, 2, subject = "alpha")
        )
        db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = ana,
                type = OperationType.MOVE,
                folderPath = "INBOX",
                uid = 1,
                payload = "Sent",
                createdAt = Instant.EPOCH
            )
        )

        assertEquals(listOf(2L), uids("alpha"))
    }

    @Test
    fun `the snippet shows the body around a match the stored snippet does not have`() = runTest {
        insert(
            message(
                ana,
                1,
                subject = "Hello",
                snippet = "intro",
                bodyText =
                    "intro " + "x ".repeat(100) + "marmalade jar"
            )
        )

        val item = listing.observe(
            SearchQueryParser.parse("marmalade "),
            SearchScope.AllAccounts,
            10,
            ZoneOffset.UTC
        )
            .first().items.single()

        assertTrue("marmalade" in item.snippet)
    }

    @Test
    fun `results update when mail changes`() = runTest {
        insert(message(ana, 1, subject = "alpha", seen = false))
        val flow = listing.observe(
            SearchQueryParser.parse("is:unread "),
            SearchScope.AllAccounts,
            10,
            ZoneOffset.UTC
        )

        flow.test {
            assertEquals(1, awaitItem().items.size)
            val id = db.messageDao().get(ana, "INBOX", 1)!!.id
            db.messageDao().setFlags(
                id,
                seen = true,
                flagged = false,
                answered = false,
                pendingSync = false
            )
            assertEquals(0, awaitItem().items.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rows by id keep only visible ones, newest first`() = runTest {
        insert(
            message(ana, 1, subject = "a", sentAt = 1_000),
            message(ana, 2, subject = "b", sentAt = 2_000)
        )
        val ids = listOf(1L, 2L).map { db.messageDao().get(ana, "INBOX", it)!!.id }

        val items = listing.observeByIds(ids, SearchQuery.EMPTY).first()

        assertEquals(listOf("b", "a"), items.map { it.subject })
        assertEquals(emptyList<Any>(), listing.observeByIds(emptyList(), SearchQuery.EMPTY).first())
    }

    @Test
    fun `hostile input never breaks the statement or touches the data`() = runTest {
        insert(message(ana, 1, subject = "alpha"))
        val hostile = listOf(
            "'; DROP TABLE message; --",
            "\" OR 1=1 --",
            "alpha\" OR \"x",
            "alpha NEAR/2 beta",
            "a* b* \"unterminated",
            "(((",
            ")",
            "subject:\"",
            "from:\"; DELETE FROM message; ",
            "to:%' OR '1'='1 ",
            "label:\\ ",
            "label:' OR 1=1 --",
            "\u0000\u0001",
            "-\"",
            "-",
            "\"",
            "\"\"",
            "*",
            "senderName:alpha",
            "NOT alpha",
            "alpha AND OR NOT",
            "{subject senderName}: alpha",
            "^alpha",
            "after:2026-02-30",
            "😀😀",
            "x".repeat(5_000)
        )

        hostile.forEach { text ->
            listing.observe(
                SearchQueryParser.parse(text),
                SearchScope.AllAccounts,
                10,
                ZoneOffset.UTC
            ).first()
            listing.observe(
                SearchQueryParser.parse("$text "),
                SearchScope.AllAccounts,
                10,
                ZoneOffset.UTC
            ).first()
        }

        assertEquals(1, db.messageDao().serverUids(ana, "INBOX").size)
        assertEquals(listOf(1L), uids("alpha"))
    }
}
