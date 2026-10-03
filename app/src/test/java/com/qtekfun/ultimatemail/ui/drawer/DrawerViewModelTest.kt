// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.FakeAttachmentStorage
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
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
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
class DrawerViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val status = SyncStatusStore()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
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

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = DrawerViewModel(
        AccountListing(db),
        FolderListing(db),
        AccountRemoval(db, vault, FakeAttachmentStorage(), Dispatchers.Unconfined),
        status,
        scheduler,
        saved
    )

    @Test
    fun `without accounts the state is loaded and empty`() = runTest {
        viewModel().state.test {
            val state = awaitLoaded()
            assertTrue(state.loaded)
            assertTrue(state.accounts.isEmpty())
            assertNull(state.selected)
            assertTrue(state.special.isEmpty())
            assertTrue(state.folders.isEmpty())
            assertNull(state.defaultScope)
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
            assertEquals(listOf("INBOX"), state.special.map { it.path })
            assertEquals(listOf("Work"), state.folders.map { it.path })
            assertEquals(listOf(1), state.special.map { it.unread })
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
            assertTrue(state.special.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(second, saved.get<Long>("selectedAccount"))
    }

    @Test
    fun `switching account returns the inbox of that account`() = runTest {
        db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(second, "Posteingang", FolderRole.INBOX)))
        val model = viewModel()

        assertEquals(InboxScope.Folder(second, "Posteingang"), model.switchAccount(second))
        assertEquals(second, model.state.first { it.selected?.id == second }.selected?.id)
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
            assertTrue(state.special.isEmpty())
            assertTrue(state.folders.isEmpty())
        }
    }

    @Test
    fun `the default scope is the inbox of the only account and the unified inbox otherwise`() =
        runTest {
            val first = db.accountDao().insert(account("a@example.test"))
            db.folderDao().upsert(listOf(folder(first, "Posteingang", FolderRole.INBOX)))

            viewModel().state.test {
                assertEquals(
                    InboxScope.Folder(first, "Posteingang"),
                    awaitState { it.defaultScope != null }.defaultScope
                )
                db.accountDao().insert(account("b@example.test"))
                assertEquals(
                    InboxScope.Unified,
                    awaitState { it.defaultScope == InboxScope.Unified }.defaultScope
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `parents start collapsed and toggling opens and closes them`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(
            listOf(
                folder(id, "Work", FolderRole.OTHER),
                folder(id, "Work/Invoices", FolderRole.OTHER)
            )
        )
        val model = viewModel()

        model.state.test {
            assertEquals(
                listOf("Work"),
                awaitState {
                    it.folders.isNotEmpty()
                }.folders.map { it.path }
            )
            model.toggleFolder("Work")
            val open = awaitState { it.folders.size == 2 }
            assertEquals(listOf("Work", "Work/Invoices"), open.folders.map { it.path })
            assertEquals(setOf("Work"), open.expanded)
            model.toggleFolder("Work")
            assertEquals(1, awaitState { it.folders.size == 1 }.folders.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `expanded parents are remembered in saved state`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(
            listOf(
                folder(id, "Work", FolderRole.OTHER),
                folder(id, "Work/Invoices", FolderRole.OTHER)
            )
        )
        val saved = SavedStateHandle()
        viewModel(saved).apply {
            state.test {
                awaitState { it.folders.isNotEmpty() }
                toggleFolder("Work")
                cancelAndIgnoreRemainingEvents()
            }
        }

        viewModel(saved).state.test {
            assertEquals(
                listOf("Work", "Work/Invoices"),
                awaitState { it.folders.size == 2 }.folders.map { it.path }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing a nested folder opens its parents and follows its account`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(first),
                folder(second, "Work", FolderRole.OTHER),
                folder(second, "Work/Invoices", FolderRole.OTHER)
            )
        )
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.onScopeShown(InboxScope.Folder(second, "Work/Invoices"))
            val state = awaitState { it.selected?.id == second && it.folders.size == 2 }
            assertEquals(listOf("Work", "Work/Invoices"), state.folders.map { it.path })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing the unified inbox leaves the selected account alone`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        db.accountDao().insert(account("b@example.test"))
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.onScopeShown(InboxScope.Unified)
            expectNoEvents()
            assertEquals(first, model.state.value.selected?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the sync line follows the status of the selected account`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        val model = viewModel()
        val at = Instant.parse("2026-03-01T10:15:00Z")

        model.state.test {
            assertEquals(SyncLine.NeverSynced, awaitLoaded().syncLine)
            status.set(first, AccountSyncState.Syncing)
            assertEquals(SyncLine.Syncing, awaitState { it.syncLine == SyncLine.Syncing }.syncLine)
            status.set(second, AccountSyncState.ReauthenticationNeeded)
            status.set(first, AccountSyncState.Idle(at))
            assertEquals(
                SyncLine.LastSynced(at),
                awaitState {
                    it.syncLine is SyncLine.LastSynced
                }.syncLine
            )
            model.select(second)
            assertEquals(
                SyncLine.SignInAgain,
                awaitState {
                    it.syncLine == SyncLine.SignInAgain
                }.syncLine
            )
            status.set(second, AccountSyncState.Error(SyncProblem.NETWORK))
            assertEquals(
                SyncLine.Failed(SyncProblem.NETWORK),
                awaitState { it.syncLine is SyncLine.Failed }.syncLine
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh asks for a user-initiated sync of every account`() {
        viewModel().refresh()

        verify(exactly = 1) { scheduler.requestSync(null, true) }
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

    private suspend fun ReceiveTurbine<FolderMenuState>.awaitState(
        matches: (FolderMenuState) -> Boolean
    ): FolderMenuState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }

    private suspend fun ReceiveTurbine<FolderMenuState>.awaitLoaded() = awaitState { it.loaded }
}
