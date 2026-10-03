// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PendingOperationDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.pendingOperationDao()
    private var accountId = 0L
    private val now = Instant.ofEpochMilli(1_000)

    @BeforeEach
    fun setUp() = runTest { accountId = db.accountDao().insert(account()) }

    @AfterEach
    fun close() = db.close()

    private fun operation(uid: Long, type: OperationType = OperationType.SET_FLAGS) =
        PendingOperationEntity(
            accountId = accountId,
            type = type,
            folderPath = "INBOX",
            uid = uid,
            payload = "{}",
            createdAt = now
        )

    @Test
    fun `operations come back in the order they were queued`() = runTest {
        dao.enqueue(operation(2))
        dao.enqueue(operation(1, OperationType.MOVE))

        assertEquals(listOf(2L, 1L), dao.all(accountId).map { it.uid })
    }

    @Test
    fun `new operations are due now and not failed`() = runTest {
        dao.enqueue(operation(1))

        val stored = dao.all(accountId).single()
        assertEquals(now, stored.nextAttemptAt)
        assertEquals(0, stored.attempts)
        assertFalse(stored.failed)
    }

    @Test
    fun `finds the operations of one message`() = runTest {
        dao.enqueue(operation(1))
        dao.enqueue(operation(2))

        assertEquals(1, dao.forMessage(accountId, "INBOX", 2).size)
    }

    @Test
    fun `replaces a payload, deletes and counts`() = runTest {
        val id = dao.enqueue(operation(1))
        dao.replacePayload(id, "{\"seen\":true}")

        assertEquals("{\"seen\":true}", dao.all(accountId).single().payload)
        dao.observeCount(accountId).test { assertEquals(1, awaitItem()) }
        dao.delete(id)
        dao.observeCount(accountId).test { assertEquals(0, awaitItem()) }
    }
}
