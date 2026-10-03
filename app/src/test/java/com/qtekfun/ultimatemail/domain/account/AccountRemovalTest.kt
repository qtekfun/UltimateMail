// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.OperationType
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountRemovalTest {
    private val db = inMemoryDatabase()
    private val saved = mutableMapOf<Long, AccountCredentials>()
    private val vault = object : CredentialVault {
        override suspend fun save(accountId: Long, credentials: AccountCredentials) {
            saved[accountId] = credentials
        }

        override suspend fun load(accountId: Long) = saved[accountId]

        override suspend fun delete(accountId: Long) {
            saved.remove(accountId)
        }
    }
    private val removal = AccountRemoval(db, vault, Dispatchers.Unconfined)

    @AfterEach
    fun close() = db.close()

    private suspend fun populate(email: String): Long {
        val id = db.accountDao().insert(account(email))
        db.folderDao().upsert(listOf(folder(id)))
        db.messageDao().upsert(listOf(message(id, uid = 1), message(id, uid = 2)))
        db.pendingOperationDao().enqueue(
            PendingOperationEntity(
                accountId = id,
                type = OperationType.SET_FLAGS,
                folderPath = "INBOX",
                uid = 1,
                payload = "{}",
                createdAt = Instant.ofEpochMilli(1)
            )
        )
        vault.save(id, AccountCredentials(password = "secret-$email"))
        return id
    }

    @Test
    fun `removing an account deletes its credentials and all its local data`() = runTest {
        val doomed = populate("a@example.test")

        removal.remove(doomed)

        assertNull(db.accountDao().get(doomed))
        assertNull(vault.load(doomed))
        assertTrue(db.folderDao().observeAll(doomed).first().isEmpty())
        assertNull(db.messageDao().get(doomed, "INBOX", 1))
        assertNull(db.messageDao().get(doomed, "INBOX", 2))
        assertTrue(db.pendingOperationDao().all(doomed).isEmpty())
    }

    @Test
    fun `other accounts keep their data and credentials`() = runTest {
        val doomed = populate("a@example.test")
        val kept = populate("b@example.test")

        removal.remove(doomed)

        assertNotNull(db.accountDao().get(kept))
        assertEquals("secret-b@example.test", vault.load(kept)?.password)
        assertEquals(1, db.folderDao().observeAll(kept).first().size)
        assertNotNull(db.messageDao().get(kept, "INBOX", 1))
        assertEquals(1, db.pendingOperationDao().all(kept).size)
    }

    @Test
    fun `removing an unknown account is harmless`() = runTest {
        val kept = populate("b@example.test")

        removal.remove(999)

        assertNotNull(db.accountDao().get(kept))
    }
}
