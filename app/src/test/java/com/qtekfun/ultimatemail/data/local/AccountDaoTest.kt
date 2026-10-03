// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.accountDao()

    @AfterEach
    fun close() = db.close()

    @Test
    fun `new accounts get a 90 day offline window and an enabled signature`() = runTest {
        val id = dao.insert(account())

        val stored = dao.get(id)!!
        assertEquals(90, stored.offlineWindowDays)
        assertTrue(stored.signatureEnabled)
        assertTrue(stored.signatureBeforeQuote)
        assertEquals("", stored.signature)
    }

    @Test
    fun `each account keeps its own signature`() = runTest {
        val first = dao.insert(account("a@example.test"))
        val second = dao.insert(account("b@example.test"))

        dao.update(dao.get(first)!!.copy(signature = "Ana", signatureBeforeQuote = false))
        dao.update(dao.get(second)!!.copy(signature = "Work", signatureEnabled = false))

        assertEquals("Ana", dao.get(first)!!.signature)
        assertFalse(dao.get(first)!!.signatureBeforeQuote)
        assertEquals("Work", dao.get(second)!!.signature)
        assertFalse(dao.get(second)!!.signatureEnabled)
    }

    @Test
    fun `a null offline window keeps the whole mailbox`() = runTest {
        val id = dao.insert(account().copy(offlineWindowDays = null))

        assertNull(dao.get(id)!!.offlineWindowDays)
    }

    @Test
    fun `observes accounts and a single account`() = runTest {
        val id = dao.insert(account())

        dao.observeAll().test { assertEquals(1, awaitItem().size) }
        dao.observe(id).test { assertEquals("ana@example.test", awaitItem()!!.email) }
    }

    @Test
    fun `deleting an account removes all its data`() = runTest {
        val id = dao.insert(account())
        db.folderDao().upsert(listOf(folder(id)))
        db.messageDao().upsert(listOf(message(id, 1)))

        dao.delete(id)

        assertNull(dao.get(id))
        assertNull(db.folderDao().get(id, "INBOX"))
        assertNull(db.messageDao().get(id, "INBOX", 1))
    }
}
