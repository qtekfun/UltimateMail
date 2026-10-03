// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.domain.backup.MemoryPendingChoices
import com.qtekfun.ultimatemail.domain.mail.MailFolder
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FolderCatalogPendingChoicesTest {
    private val db = inMemoryDatabase()
    private val pending = MemoryPendingChoices()
    private val catalog = FolderCatalog(db.folderDao(), pending)
    private val session = mockk<MailSession>()

    @AfterEach
    fun close() = db.close()

    private fun folder(
        path: String,
        role: MailFolderRole = MailFolderRole.OTHER,
        selectable: Boolean = true
    ) = MailFolder(path, path.substringAfterLast('/'), '/', role, selectable)

    private suspend fun refresh(vararg folders: MailFolder): Long {
        val id = db.accountDao().insert(account())
        listFolders(*folders)
        return id.also { catalog.refresh(db.accountDao().get(it)!!, session) }
    }

    private fun listFolders(vararg folders: MailFolder) {
        coEvery { session.listFolders() } returns MailResult.Success(folders.toList())
    }

    private suspend fun synced(id: Long): Map<String, Boolean> {
        val folders = db.folderDao().all(id)
        return folders.associate { it.path to it.syncEnabled }
    }

    @Test
    fun `the choices of an imported backup are applied by the first folder listing`() = runTest {
        val id = db.accountDao().insert(account())
        pending.save(id, mapOf("Work" to false, "Sent" to false, "Archive" to true))
        listFolders(
            folder("INBOX", MailFolderRole.INBOX),
            folder("Work"),
            folder("Sent", MailFolderRole.SENT),
            folder("Archive", MailFolderRole.ARCHIVE)
        )

        catalog.refresh(db.accountDao().get(id)!!, session)

        assertEquals(
            mapOf("INBOX" to true, "Work" to false, "Sent" to false, "Archive" to true),
            synced(id)
        )
        assertTrue(pending.peek(id).isEmpty())
    }

    @Test
    fun `the inbox and containers keep their own rule and unknown paths are dropped`() = runTest {
        val id = db.accountDao().insert(account())
        pending.save(
            id,
            mapOf("INBOX" to false, "[Gmail]" to true, "Gone" to false, "All" to true)
        )
        listFolders(
            folder("INBOX", MailFolderRole.INBOX),
            folder("[Gmail]", selectable = false),
            folder("All", MailFolderRole.ALL_MAIL)
        )

        catalog.refresh(db.accountDao().get(id)!!, session)

        assertEquals(mapOf("INBOX" to true, "[Gmail]" to false, "All" to true), synced(id))
        assertTrue(pending.peek(id).isEmpty())
    }

    @Test
    fun `choices are applied once and later listings leave the user's changes alone`() = runTest {
        val id = db.accountDao().insert(account())
        pending.save(id, mapOf("Work" to false))
        listFolders(folder("INBOX", MailFolderRole.INBOX), folder("Work"))
        catalog.refresh(db.accountDao().get(id)!!, session)

        db.folderDao().setSyncEnabled(id, "Work", true)
        catalog.refresh(db.accountDao().get(id)!!, session)

        assertTrue(synced(id).getValue("Work"))
    }

    @Test
    fun `choices wait when the server could not list its folders`() = runTest {
        val id = db.accountDao().insert(account())
        pending.save(id, mapOf("Work" to false))

        listFolders()
        catalog.refresh(db.accountDao().get(id)!!, session)

        assertEquals(mapOf("Work" to false), pending.peek(id))
    }

    @Test
    fun `accounts without choices get the usual defaults`() = runTest {
        val id = refresh(
            folder("INBOX", MailFolderRole.INBOX),
            folder("All", MailFolderRole.ALL_MAIL),
            folder("Work")
        )

        assertEquals(mapOf("INBOX" to true, "All" to false, "Work" to true), synced(id))
        assertFalse(pending.stored.containsKey(id))
    }

    @Test
    fun `a catalog built without pending choices works as before`() = runTest {
        val plain = FolderCatalog(db.folderDao())
        val id = db.accountDao().insert(account())
        listFolders(folder("INBOX", MailFolderRole.INBOX))

        plain.refresh(db.accountDao().get(id)!!, session)

        assertEquals(mapOf("INBOX" to true), synced(id))
    }
}
