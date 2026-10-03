// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.dao.FolderUnread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FolderDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.folderDao()
    private var accountId = 0L

    @BeforeEach
    fun setUp() = runTest { accountId = db.accountDao().insert(account()) }

    @AfterEach
    fun close() = db.close()

    @Test
    fun `upserting replaces a folder with the same path`() = runTest {
        dao.upsert(listOf(folder(accountId)))
        dao.upsert(listOf(folder(accountId).copy(name = "Inbox")))

        dao.observeAll(accountId).test {
            assertEquals(listOf("Inbox"), awaitItem().map { it.name })
        }
    }

    @Test
    fun `folders of two accounts with the same path do not clash`() = runTest {
        val other = db.accountDao().insert(account("b@example.test"))
        dao.upsert(listOf(folder(accountId), folder(other)))

        assertEquals(1, dao.observeAll(other).first().size)
    }

    @Test
    fun `only folders enabled for sync are syncable`() = runTest {
        dao.upsert(listOf(folder(accountId), folder(accountId, "Spam").copy(syncEnabled = false)))

        assertEquals(listOf("INBOX"), dao.syncable(accountId).map { it.path })
    }

    @Test
    fun `deleting vanished folders removes their messages`() = runTest {
        dao.upsert(listOf(folder(accountId), folder(accountId, "Old")))
        db.messageDao().upsert(listOf(message(accountId, 1, folderPath = "Old")))

        dao.deleteAllExcept(accountId, listOf("INBOX"))

        assertNull(dao.get(accountId, "Old"))
        assertNull(db.messageDao().get(accountId, "Old", 1))
    }

    @Test
    fun `stores the IMAP sync state`() = runTest {
        dao.upsert(listOf(folder(accountId)))

        dao.setSyncState(accountId, "INBOX", uidValidity = 7, uidNext = 42, highestModSeq = 99)

        val state = dao.get(accountId, "INBOX")!!
        assertEquals(
            Triple(7L, 42L, 99L),
            Triple(state.uidValidity, state.uidNext, state.highestModSeq)
        )
    }

    @Test
    fun `counts unread messages per folder`() = runTest {
        dao.upsert(listOf(folder(accountId), folder(accountId, "Work")))
        db.messageDao().upsert(
            listOf(
                message(accountId, 1),
                message(accountId, 2, seen = true),
                message(accountId, 3, "Work")
            )
        )

        dao.observeUnread(accountId).test {
            assertEquals(
                setOf(FolderUnread("INBOX", 1), FolderUnread("Work", 1)),
                awaitItem().toSet()
            )
        }
    }
}
