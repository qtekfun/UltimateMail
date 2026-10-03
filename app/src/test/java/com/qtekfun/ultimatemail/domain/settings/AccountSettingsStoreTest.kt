// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccountSettingsStoreTest {
    private lateinit var db: UltimateMailDatabase
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val downloads = MemoryOfflineDownloads()
    private lateinit var store: AccountSettingsStore

    @BeforeEach
    fun setUp() {
        db = inMemoryDatabase()
        store = AccountSettingsStore(db, scheduler, downloads, Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() = db.close()

    private suspend fun addAccount(email: String = "ana@example.test") =
        db.accountDao().insert(account(email))

    private val profile = AccountProfile(
        displayName = "Ana Gil",
        signature = "Ana\nAcme",
        signatureEnabled = true,
        signatureBeforeQuote = false
    )

    @Test
    fun `a new account shows the defaults of the entity`() = runTest {
        val id = addAccount()

        val settings = store.observe(id).first()!!

        assertEquals(id, settings.id)
        assertEquals("ana@example.test", settings.email)
        assertEquals(AccountProfile("Ana", "", true, true), settings.profile)
        assertEquals(OfflineWindow.DAYS_90, settings.offlineWindow)
    }

    @Test
    fun `downloading for offline is on by default and can be switched`() = runTest {
        val id = addAccount()
        assertTrue(store.observe(id).first()!!.downloadForOffline)

        store.setDownloadForOffline(id, false)
        assertFalse(store.observe(id).first()!!.downloadForOffline)
        verify(exactly = 0) { scheduler.requestSync(id) }

        store.setDownloadForOffline(id, true)
        assertTrue(store.observe(id).first()!!.downloadForOffline)
        verify { scheduler.requestSync(id) }
    }

    @Test
    fun `switching downloads for an account that is gone does nothing`() = runTest {
        store.setDownloadForOffline(99, true)

        assertTrue(downloads.isEnabled(99))
        verify(exactly = 0) { scheduler.requestSync(any()) }
    }

    @Test
    fun `an unknown account has no settings`() = runTest {
        assertNull(store.observe(404).first())
    }

    @Test
    fun `saving stores the cleaned profile and leaves the rest of the account alone`() = runTest {
        val id = addAccount()

        val result = store.saveProfile(
            id,
            profile.copy(displayName = " Ana Gil ", signature = "Ana  \nAcme\n\n")
        )

        assertEquals(SaveResult.Saved, result)
        val stored = db.accountDao().get(id)!!
        assertEquals(profile.displayName, stored.displayName)
        assertEquals("Ana\nAcme", stored.signature)
        assertFalse(stored.signatureBeforeQuote)
        assertEquals("imap.example.test", stored.imapHost)
        assertEquals(90, stored.offlineWindowDays)
        assertEquals(profile, store.observe(id).first()!!.profile)
    }

    @Test
    fun `an invalid profile is not stored`() = runTest {
        val id = addAccount()
        val tooLong = profile.copy(signature = "x".repeat(ProfileRules.MAX_SIGNATURE_LENGTH + 1))

        val result = store.saveProfile(id, tooLong)

        assertEquals(SaveResult.Invalid(setOf(ProfileError.SIGNATURE_TOO_LONG)), result)
        assertEquals("", db.accountDao().get(id)!!.signature)
    }

    @Test
    fun `saving for an account that is gone reports it`() = runTest {
        assertEquals(SaveResult.NotFound, store.saveProfile(404, profile))
    }

    @Test
    fun `the offline window is stored as days, and null for the whole mailbox`() = runTest {
        val id = addAccount()

        OfflineWindow.entries.forEach {
            store.setOfflineWindow(id, it)

            assertEquals(it.days, db.accountDao().get(id)!!.offlineWindowDays)
            assertEquals(it, store.observe(id).first()!!.offlineWindow)
        }
    }

    @Test
    fun `changing the window of an unknown account does nothing`() = runTest {
        val id = addAccount()

        store.setOfflineWindow(404, OfflineWindow.YEAR)

        assertEquals(90, db.accountDao().get(id)!!.offlineWindowDays)
    }

    @Test
    fun `folders are listed with their sync choice`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(
            listOf(
                folder(id),
                folder(id, "Work", FolderRole.OTHER).copy(syncEnabled = false),
                folder(id, "Labels/Tax", FolderRole.OTHER).copy(isLabel = true)
            )
        )

        val folders = store.observeFolders(id).first()

        assertEquals(listOf("INBOX", "Labels/Tax", "Work"), folders.map { it.path })
        assertEquals(listOf(true, true, false), folders.map { it.syncEnabled })
        assertEquals(listOf(false, true, false), folders.map { it.isLabel })
    }

    @Test
    fun `turning a folder off stops its sync without a sync request`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))

        store.setFolderSync(id, "Work", false)

        assertFalse(db.folderDao().get(id, "Work")!!.syncEnabled)
        assertEquals(listOf("INBOX"), db.folderDao().syncable(id).map { it.path })
        verify(exactly = 0) { scheduler.requestSync(any(), any()) }
    }

    @Test
    fun `turning a folder on syncs the account right away`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(
            listOf(folder(id), folder(id, "Work", FolderRole.OTHER).copy(syncEnabled = false))
        )

        store.setFolderSync(id, "Work", true)

        assertTrue(db.folderDao().get(id, "Work")!!.syncEnabled)
        verify(exactly = 1) { scheduler.requestSync(id) }
    }

    @Test
    fun `turning on a folder that is already on does not ask for a sync`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))

        store.setFolderSync(id, "Work", true)

        verify(exactly = 0) { scheduler.requestSync(any(), any()) }
    }

    @Test
    fun `the inbox cannot be turned off`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(listOf(folder(id)))

        store.setFolderSync(id, "INBOX", false)

        assertTrue(db.folderDao().get(id, "INBOX")!!.syncEnabled)
        assertFalse(store.observeFolders(id).first().single().canToggle)
    }

    @Test
    fun `other folders can be toggled, and an unknown folder is ignored`() = runTest {
        val id = addAccount()
        db.folderDao().upsert(listOf(folder(id, "Work", FolderRole.OTHER)))

        store.setFolderSync(id, "Missing", true)

        assertTrue(store.observeFolders(id).first().single().canToggle)
        verify(exactly = 0) { scheduler.requestSync(any(), any()) }
    }

    @Test
    fun `the folders of another account are untouched`() = runTest {
        val a = addAccount("a@example.test")
        val b = addAccount("b@example.test")
        db.folderDao().upsert(
            listOf(folder(a, "Work", FolderRole.OTHER), folder(b, "Work", FolderRole.OTHER))
        )

        store.setFolderSync(a, "Work", false)

        assertTrue(db.folderDao().get(b, "Work")!!.syncEnabled)
    }
}
