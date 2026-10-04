// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InboxListingTest {
    private lateinit var db: UltimateMailDatabase
    private lateinit var listing: InboxListing

    @BeforeEach
    fun setUp() {
        db = inMemoryDatabase()
        listing = InboxListing(db, SyncStatusStore())
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `a conversation row carries the newest message and the counters of the thread`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id)))
        db.messageDao().upsert(
            listOf(
                message(id, 1, threadId = "t", seen = true, sentAt = 1_000),
                message(
                    id,
                    2,
                    threadId = "t",
                    seen = false,
                    sentAt = 2_000,
                    subject = "Newest",
                    senderName = "Ana",
                    senderAddress = "ana@example.test",
                    snippet = "Hello there",
                    flagged = true,
                    hasAttachments = true,
                    labels = listOf("Work"),
                    pendingSync = true
                )
            )
        )

        val item = listing.observe(InboxScope.Folder(id, "INBOX"), 50).first().single()

        assertEquals("Newest", item.subject)
        assertEquals("Ana", item.senderName)
        assertEquals("ana@example.test", item.senderAddress)
        assertEquals("Hello there", item.snippet)
        assertEquals(Instant.ofEpochMilli(2_000), item.sentAt)
        assertEquals(2, item.messageCount)
        assertEquals(1, item.unreadCount)
        assertTrue(item.unread && item.flagged && item.hasAttachments && item.pendingSync)
        assertEquals(listOf("Work"), item.labels)
        assertEquals("$id|INBOX|t", item.key)
    }

    @Test
    fun `the sender falls back to the address`() {
        val item = ConversationItem(
            1, "INBOX", "t", 1, "", "ana@example.test", "s", "", Instant.EPOCH, 1, 0, false, false,
            emptyList(), false
        )

        assertEquals("ana@example.test", item.sender)
        assertEquals("Ana", item.copy(senderName = "Ana").sender)
    }

    @Test
    fun `the limit caps the rows and keeps the newest`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id)))
        db.messageDao().upsert((1L..5L).map { message(id, it) })

        val items = listing.observe(InboxScope.Folder(id, "INBOX"), 3).first()

        assertEquals(listOf("Subject 5", "Subject 4", "Subject 3"), items.map { it.subject })
    }

    @Test
    fun `the unified inbox merges the inboxes of all accounts and nothing else`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(
            listOf(folder(first), folder(second), folder(first, "Work", FolderRole.OTHER))
        )
        db.messageDao().upsert(
            listOf(
                message(first, 1, sentAt = 1_000),
                message(second, 1, sentAt = 3_000),
                message(first, 2, folderPath = "Work", sentAt = 2_000)
            )
        )

        val items = listing.observe(InboxScope.Unified, 50).first()

        assertEquals(listOf(second, first), items.map { it.accountId })
        assertTrue(items.all { it.folderPath == "INBOX" })
    }

    @Test
    fun `a folder is synced once it has a uid validity`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))
        db.folderDao().setSyncState(id, "Work", uidValidity = 5, uidNext = 1, highestModSeq = null)

        val inbox = listing.observeStatus(InboxScope.Folder(id, "INBOX")).first()
        val work = listing.observeStatus(InboxScope.Folder(id, "Work")).first()

        assertFalse(inbox.synced)
        assertEquals(FolderRole.INBOX, inbox.folderRole)
        assertEquals("INBOX", inbox.folderName)
        assertTrue(work.synced)
        assertEquals("Work", work.folderName)
    }

    @Test
    fun `a missing folder has no name and is not synced`() = runTest {
        val status = listing.observeStatus(InboxScope.Folder(99, "Gone")).first()

        assertNull(status.folderName)
        assertFalse(status.synced)
    }

    @Test
    fun `the unified status is synced when any inbox was synced`() = runTest {
        assertFalse(listing.observeStatus(InboxScope.Unified).first().synced)

        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(first), folder(second)))
        db.folderDao().setSyncState(first, "INBOX", 1, 1, null)

        val status = listing.observeStatus(InboxScope.Unified).first()

        assertTrue(status.synced)
        assertNull(status.folderName)
    }

    @Test
    fun `syncing a folder other than an inbox does not make the unified inbox synced`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))
        db.folderDao().setSyncState(id, "Work", 1, 1, null)

        assertFalse(listing.observeStatus(InboxScope.Unified).first().synced)
    }
}
