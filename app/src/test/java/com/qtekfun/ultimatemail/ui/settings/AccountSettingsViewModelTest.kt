// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.FakeAttachmentStorage
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.settings.AccountSettingsStore
import com.qtekfun.ultimatemail.domain.settings.MemoryOfflineDownloads
import com.qtekfun.ultimatemail.domain.settings.OfflineWindow
import com.qtekfun.ultimatemail.domain.settings.ProfileError
import com.qtekfun.ultimatemail.domain.settings.ProfileRules
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val removedSecrets = mutableListOf<Long>()
    private val vault = object : CredentialVault {
        override suspend fun save(accountId: Long, credentials: AccountCredentials) = Unit

        override suspend fun load(accountId: Long): AccountCredentials? = null

        override suspend fun delete(accountId: Long) {
            removedSecrets += accountId
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
    }

    @AfterEach
    fun tearDown() {
        // Stop the models before the database goes away, or a late emission fails the next test.
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    private val created = mutableListOf<AccountSettingsViewModel>()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = AccountSettingsViewModel(
        AccountSettingsStore(
            db,
            mockk<SyncScheduler>(relaxed = true),
            MemoryOfflineDownloads(),
            Dispatchers.Unconfined
        ),
        AccountRemoval(db, vault, FakeAttachmentStorage(), Dispatchers.Unconfined),
        saved
    ).also { created += it }

    @Test
    fun `before an account is shown nothing is loaded`() = runTest {
        viewModel().state.test {
            val state = awaitItem()

            assertFalse(state.loaded)
            assertFalse(state.found)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unknown account is loaded but not found`() = runTest {
        val model = viewModel()

        model.state.test {
            model.show(404)

            val state = awaitState { it.loaded }
            assertFalse(state.found)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an account shows its stored profile, offline window and folders`() = runTest {
        val id = db.accountDao().insert(account().copy(signature = "Ana", offlineWindowDays = 365))
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))
        val model = viewModel()

        model.state.test {
            model.show(id)

            val state = awaitState { it.found && it.folders.size == 2 }
            assertEquals("ana@example.test", state.email)
            assertEquals("Ana", state.profile.displayName)
            assertEquals("Ana", state.profile.signature)
            assertEquals(OfflineWindow.YEAR, state.offlineWindow)
            assertEquals(ProfileStatus.SAVED, state.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing marks the form unsaved and saving stores it`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.onSignatureChange("Ana  \nAcme")
            model.onBeforeQuoteChange(false)
            model.onNameChange("Ana Gil")
            val edited = awaitState {
                it.status == ProfileStatus.UNSAVED &&
                    it.profile.displayName == "Ana Gil"
            }
            assertEquals("Ana  \nAcme", edited.profile.signature)
            assertEquals("Ana", db.accountDao().get(id)!!.displayName)

            model.save()

            val saved = awaitState {
                it.status == ProfileStatus.SAVED &&
                    it.profile.displayName == "Ana Gil"
            }
            assertEquals("Ana\nAcme", saved.profile.signature)
            cancelAndIgnoreRemainingEvents()
        }
        val stored = db.accountDao().get(id)!!
        assertEquals("Ana Gil", stored.displayName)
        assertEquals("Ana\nAcme", stored.signature)
        assertFalse(stored.signatureBeforeQuote)
    }

    @Test
    fun `turning the signature off is part of the same form`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.onSignatureEnabledChange(false)
            awaitState { it.status == ProfileStatus.UNSAVED && !it.profile.signatureEnabled }
            model.save()
            awaitState { it.status == ProfileStatus.SAVED && !it.profile.signatureEnabled }
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(db.accountDao().get(id)!!.signatureEnabled)
    }

    @Test
    fun `an invalid form reports its errors and cannot be saved`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.onSignatureChange("x".repeat(ProfileRules.MAX_SIGNATURE_LENGTH + 1))
            val state = awaitState { it.status == ProfileStatus.INVALID }
            assertEquals(setOf(ProfileError.SIGNATURE_TOO_LONG), state.errors)

            model.save()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("", db.accountDao().get(id)!!.signature)
    }

    @Test
    fun `typing the stored text back makes the form saved again`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.onNameChange("Other")
            awaitState { it.status == ProfileStatus.UNSAVED }
            model.onNameChange("Ana")

            assertEquals(ProfileStatus.SAVED, awaitState { it.profile.displayName == "Ana" }.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving without changes does nothing`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.save()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing another account drops the edits of the first`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test").copy(displayName = "Bea"))
        val model = viewModel()

        model.state.test {
            model.show(first)
            awaitState { it.found }
            model.onNameChange("Edited")
            awaitState { it.status == ProfileStatus.UNSAVED }

            model.show(second)

            val state = awaitState { it.email == "b@example.test" }
            assertEquals("Bea", state.profile.displayName)
            assertEquals(ProfileStatus.SAVED, state.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing the same account again keeps the edits`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }
            model.onNameChange("Edited")
            awaitState { it.status == ProfileStatus.UNSAVED }

            model.show(id)

            assertEquals("Edited", model.state.value.profile.displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the account being shown is remembered`() = runTest {
        val id = db.accountDao().insert(account())
        val saved = SavedStateHandle()
        viewModel(saved).show(id)

        viewModel(saved).state.test {
            assertEquals("ana@example.test", awaitState { it.found }.email)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the offline window is stored when chosen`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.onOfflineWindowChange(OfflineWindow.ALL)

            assertEquals(
                OfflineWindow.ALL,
                awaitState {
                    it.offlineWindow == OfflineWindow.ALL
                }.offlineWindow
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(db.accountDao().get(id)!!.offlineWindowDays)
    }

    @Test
    fun `the download for offline switch shows on and can be turned off`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()
        model.state.test {
            awaitItem()
            model.show(id)
            assertTrue(awaitState { it.found }.downloadForOffline)

            model.onDownloadForOfflineChange(false)

            assertFalse(awaitState { !it.downloadForOffline }.downloadForOffline)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a folder toggle is stored`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id, "Work", FolderRole.OTHER)))
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.folders.isNotEmpty() }

            model.onFolderSyncChange("Work", false)

            val state = awaitState { it.folders.all { folder -> !folder.syncEnabled } }
            assertEquals("Work", state.folders.single().path)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `without an account shown the actions do nothing`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            awaitItem()
            model.onNameChange("x")
            model.save()
            model.onOfflineWindowChange(OfflineWindow.YEAR)
            model.onDownloadForOfflineChange(false)
            model.onFolderSyncChange("INBOX", false)
            model.confirmRemoval()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Ana", db.accountDao().get(id)!!.displayName)
        assertTrue(removedSecrets.isEmpty())
    }

    @Test
    fun `removal asks first, can be cancelled, and then deletes the account`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            model.show(id)
            awaitState { it.found }

            model.requestRemoval()
            assertTrue(awaitState { it.confirmingRemoval }.confirmingRemoval)
            model.dismissRemoval()
            assertFalse(awaitState { !it.confirmingRemoval }.confirmingRemoval)
            assertEquals(id, db.accountDao().get(id)?.id)

            model.requestRemoval()
            awaitState { it.confirmingRemoval }
            model.confirmRemoval()

            val gone = awaitState { it.loaded && !it.found }
            assertFalse(gone.confirmingRemoval)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(db.accountDao().get(id))
        assertEquals(listOf(id), removedSecrets)
    }

    private suspend fun ReceiveTurbine<AccountSettingsState>.awaitState(
        matches: (AccountSettingsState) -> Boolean
    ): AccountSettingsState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }
}
