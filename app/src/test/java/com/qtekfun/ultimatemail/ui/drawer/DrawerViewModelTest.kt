// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.folders

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.folder.FolderListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class FolderListViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val deletedSecrets = mutableListOf<Long>()
    private val vault = object : CredentialVault {
        override suspend fun save(accountId: Long, credentials: AccountCredentials) = Unit

        override suspend fun load(accountId: Long): AccountCredentials? = null

        override suspend fun delete(accountId: Long) {
            deletedSecrets += accountId
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
    }

    @AfterEach
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = FolderListViewModel(
        AccountListing(db),
        FolderListing(db),
        AccountRemoval(db, vault, Dispatchers.Unconfined),
        saved
    )

    @Test
    fun `without accounts the state is loaded and empty`() = runTest {
        viewModel().state.test {
            val state = awaitLoaded()
            assertTrue(state.loaded)
            assertTrue(state.accounts.isEmpty())
            assertNull(state.selected)
            assertTrue(state.folders.isEmpty())
        }
    }

    @Test
    fun `the first account is selected and its folders are shown from Room`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(first, "Work", FolderRole.OTHER),
                folder(first),
                folder(second, "Other", FolderRole.OTHER)
            )
        )
        db.messageDao().upsert(listOf(message(first, 1), message(first, 2, seen = true)))

        viewModel().state.test {
            val state = awaitLoaded()
            assertEquals(first, state.selected?.id)
            assertEquals(listOf("INBOX", "Work"), state.folders.map { it.path })
            assertEquals(listOf(1, 0), state.folders.map { it.unread })
            assertEquals(2, state.accounts.size)
        }
    }

    @Test
    fun `selecting another account switches the folders and is remembered`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(first), folder(second, "Other", FolderRole.OTHER)))
        val saved = SavedStateHandle()
        val model = viewModel(saved)

        model.state.test {
            awaitLoaded()
            model.select(second)
            val state = awaitState { it.selected?.id == second && it.folders.isNotEmpty() }
            assertEquals(listOf("Other"), state.folders.map { it.path })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(second, saved.get<Long>("selectedAccount"))
    }

    @Test
    fun `a remembered account that no longer exists falls back to the first`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val saved = SavedStateHandle(mapOf("selectedAccount" to 99L))

        viewModel(saved).state.test {
            assertEquals(first, awaitLoaded().selected?.id)
        }
    }

    @Test
    fun `an account without synced folders shows an empty list`() = runTest {
        db.accountDao().insert(account())

        viewModel().state.test {
            val state = awaitLoaded()
            assertTrue(state.selected != null)
            assertTrue(state.folders.isEmpty())
        }
    }

    @Test
    fun `removal asks first and can be dismissed`() = runTest {
        val id = db.accountDao().insert(account())
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.requestRemoval()
            assertTrue(awaitState { it.confirmingRemoval }.confirmingRemoval)
            model.dismissRemoval()
            assertFalse(awaitState { !it.confirmingRemoval }.confirmingRemoval)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, db.accountDao().observeAll().first().size)
        assertTrue(deletedSecrets.isEmpty())
        assertEquals(id, db.accountDao().observeAll().first().single().id)
    }

    @Test
    fun `confirming removal deletes the account, its credentials and its data`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(first)))
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.requestRemoval()
            model.confirmRemoval()
            val state = awaitState { it.accounts.size == 1 && !it.confirmingRemoval }
            assertEquals(second, state.selected?.id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(first), deletedSecrets)
        assertEquals(listOf(second), db.accountDao().observeAll().first().map { it.id })
        assertTrue(db.folderDao().observeAll(first).first().isEmpty())
    }

    @Test
    fun `confirming without an account does nothing`() = runTest {
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.requestRemoval()
            model.confirmRemoval()
            // The dialog never shows without an account to remove.
            expectNoEvents()
            assertFalse(model.state.value.confirmingRemoval)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(deletedSecrets.isEmpty())
    }

    private suspend fun ReceiveTurbine<FolderListState>.awaitState(
        matches: (FolderListState) -> Boolean
    ): FolderListState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }

    private suspend fun ReceiveTurbine<FolderListState>.awaitLoaded() = awaitState { it.loaded }
}
