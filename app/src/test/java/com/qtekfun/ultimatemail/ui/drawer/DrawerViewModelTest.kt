// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountListing
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
            assertTrue(state.mailboxes.inboxes.isEmpty())
            assertTrue(state.mailboxes.special.isEmpty())
            assertTrue(state.mailboxes.sections.isEmpty())
            assertNull(state.defaultScope)
        }
    }

    @Test
    fun `every account has an inbox row and the first gives the special mailboxes`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(first, "Work", FolderRole.OTHER),
                folder(first),
                folder(first, "Sent", FolderRole.SENT),
                folder(second, "Posteingang", FolderRole.INBOX),
                folder(second, "Drafts", FolderRole.DRAFTS)
            )
        )
        db.messageDao().upsert(
            listOf(
                message(first, 1),
                message(first, 2, seen = true),
                message(second, 3, folderPath = "Posteingang"),
                message(second, 4, folderPath = "Posteingang")
            )
        )

        viewModel().state.test {
            val state = awaitState { it.mailboxes.unifiedUnread == 3 }
            assertEquals(first, state.selected?.id)
            assertEquals(
                listOf(
                    InboxScope.Folder(first, "INBOX"),
                    InboxScope.Folder(second, "Posteingang")
                ),
                state.mailboxes.inboxes.map { it.scope }
            )
            assertEquals(listOf(1, 2), state.mailboxes.inboxes.map { it.unread })
            assertEquals(listOf("Sent"), state.mailboxes.special.map { it.path })
            assertEquals(2, state.accounts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting another account switches the special mailboxes and is remembered`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(
            listOf(
                folder(first),
                folder(first, "Sent", FolderRole.SENT),
                folder(second, "Trash", FolderRole.TRASH)
            )
        )
        val saved = SavedStateHandle()
        val model = viewModel(saved)

        model.state.test {
            awaitState { it.mailboxes.special.map { f -> f.path } == listOf("Sent") }
            model.select(second)
            val state = awaitState {
                it.selected?.id == second && it.mailboxes.special.isNotEmpty()
            }
            assertEquals(listOf("Trash"), state.mailboxes.special.map { it.path })
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
    fun `an account without synced folders shows an inbox row and no other mailboxes`() = runTest {
        val id = db.accountDao().insert(account())

        viewModel().state.test {
            val state = awaitLoaded()
            assertTrue(state.selected != null)
            assertEquals(
                listOf(InboxScope.Folder(id, "INBOX")),
                state.mailboxes.inboxes.map { it.scope }
            )
            assertTrue(state.mailboxes.special.isEmpty())
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
    fun `sections start collapsed and toggling opens and closes them`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))
        val model = viewModel()

        model.state.test {
            val closed = awaitState { it.mailboxes.sections.isNotEmpty() }
            assertFalse(closed.mailboxes.sections.single().open)
            assertTrue(closed.folders().isEmpty())
            model.toggleSection(id)
            val open = awaitState { it.mailboxes.sections.single().open }
            assertEquals(listOf("Work"), open.folders().map { it.path })
            model.toggleSection(id)
            val closedAgain = awaitState { !it.mailboxes.sections.single().open }
            assertTrue(closedAgain.folders().isEmpty())
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
        model.toggleSection(id)

        model.state.test {
            assertEquals(
                listOf("Work"),
                awaitState { it.folders().isNotEmpty() }.folders().map { it.path }
            )
            model.toggleFolder(id, "Work")
            val open = awaitState { it.folders().size == 2 }
            assertEquals(listOf("Work", "Work/Invoices"), open.folders().map { it.path })
            assertEquals(setOf("Work"), open.mailboxes.sections.single().expanded)
            model.toggleFolder(id, "Work")
            assertEquals(1, awaitState { it.folders().size == 1 }.folders().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `open sections and parents are remembered in saved state`() = runTest {
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
                awaitState { it.mailboxes.sections.isNotEmpty() }
                toggleSection(id)
                toggleFolder(id, "Work")
                cancelAndIgnoreRemainingEvents()
            }
        }

        viewModel(saved).state.test {
            assertEquals(
                listOf("Work", "Work/Invoices"),
                awaitState { it.folders().size == 2 }.folders().map { it.path }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing a nested folder opens its section and parents and follows its account`() =
        runTest {
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
                val state = awaitState { it.selected?.id == second && it.folders(second).size == 2 }
                assertEquals(
                    listOf("Work", "Work/Invoices"),
                    state.folders(second).map { it.path }
                )
                assertTrue(state.folders(first).isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `showing a special mailbox follows its account but leaves the sections closed`() = runTest {
        val first = db.accountDao().insert(account("a@example.test"))
        val second = db.accountDao().insert(account("b@example.test"))
        db.folderDao().upsert(listOf(folder(first), folder(second, "Sent", FolderRole.SENT)))
        val model = viewModel()

        model.state.test {
            awaitLoaded()
            model.onScopeShown(InboxScope.Folder(second, "Sent"))
            val state = awaitState { it.selected?.id == second }
            assertTrue(state.mailboxes.sections.none { it.open })
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

    private fun FolderMenuState.folders(accountId: Long? = null) =
        mailboxes.sections.first { accountId == null || it.account.id == accountId }.folders

    private suspend fun ReceiveTurbine<FolderMenuState>.awaitState(
        matches: (FolderMenuState) -> Boolean
    ): FolderMenuState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }

    private suspend fun ReceiveTurbine<FolderMenuState>.awaitLoaded() = awaitState { it.loaded }
}
