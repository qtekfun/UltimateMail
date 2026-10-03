// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

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
import com.qtekfun.ultimatemail.domain.inbox.InboxListing
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class InboxViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val refreshed = mutableListOf<Long?>()
    private var refreshGate: CompletableDeferred<Unit>? = null
    private val trigger = RefreshTrigger { accountId ->
        refreshed += accountId
        refreshGate?.await()
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

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(InboxListing(db), AccountListing(db), trigger, saved)

    private suspend fun accountWithInbox(email: String = "a@example.test"): Long {
        val id = db.accountDao().insert(account(email))
        db.folderDao().upsert(listOf(folder(id)))
        db.folderDao().setSyncState(id, "INBOX", 1, 1, null)
        return id
    }

    /** One conversation per message, oldest first, [count] of them. */
    private suspend fun conversations(accountId: Long, count: Int, seen: Boolean = false) {
        db.messageDao().upsert((1L..count).map { message(accountId, it, seen = seen) })
    }

    @Test
    fun `before a scope is shown nothing is loaded`() = runTest {
        accountWithInbox()

        viewModel().state.test {
            val state = awaitItem()
            assertFalse(state.loaded)
            assertNull(state.scope)
            assertTrue(state.conversations.isEmpty())
            assertNull(state.empty)
        }
    }

    @Test
    fun `a folder shows its conversations newest first, with its name and account`() = runTest {
        val id = accountWithInbox("ana@example.test")
        conversations(id, 3)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            val state = awaitState { it.loaded && it.conversations.size == 3 }
            assertEquals(
                listOf("Subject 3", "Subject 2", "Subject 1"),
                state.conversations.map {
                    it.subject
                }
            )
            assertEquals(FolderRole.INBOX, state.folderRole)
            assertEquals("ana@example.test", state.accountEmail)
            assertTrue(state.markers.isEmpty())
            assertNull(state.empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty folder that was never synced says so, a synced one says it is empty`() = runTest {
        val id = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(id), folder(id, "Work", FolderRole.OTHER)))
        db.folderDao().setSyncState(id, "Work", 1, 1, null)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            assertEquals(InboxEmpty.NOT_SYNCED, awaitState { it.loaded }.empty)
            model.show(InboxScope.Folder(id, "Work"))
            assertEquals(
                InboxEmpty.NO_MESSAGES,
                awaitState { it.loaded && it.folderName == "Work" }.empty
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pages load as the user nears the end and stop when the mail runs out`() = runTest {
        val id = accountWithInbox()
        conversations(id, 120)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            var state = awaitState { it.loaded && it.conversations.isNotEmpty() }
            assertEquals(InboxViewModel.PAGE_SIZE, state.conversations.size)
            assertTrue(state.hasMore)
            assertEquals("Subject 120", state.conversations.first().subject)

            model.loadMore()
            state = awaitState { it.conversations.size == 2 * InboxViewModel.PAGE_SIZE }
            assertTrue(state.hasMore)

            model.loadMore()
            state = awaitState { it.conversations.size == 120 }
            assertFalse(state.hasMore)
            assertEquals("Subject 1", state.conversations.last().subject)

            model.loadMore()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `asking for more before the current page arrived does not skip a page`() = runTest {
        val id = accountWithInbox()
        conversations(id, 200)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == InboxViewModel.PAGE_SIZE }
            model.loadMore()
            model.loadMore()
            model.loadMore()
            val state = awaitState {
                it.limit == 2 * InboxViewModel.PAGE_SIZE &&
                    it.loadedCount == it.limit
            }
            assertEquals(2 * InboxViewModel.PAGE_SIZE, state.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the list stays reactive while paging`() = runTest {
        val id = accountWithInbox()
        conversations(id, 60)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == InboxViewModel.PAGE_SIZE }
            model.loadMore()
            awaitState { it.conversations.size == 60 }

            db.messageDao().upsert(listOf(message(id, 61)))
            val state = awaitState { it.conversations.size == 61 }

            assertEquals("Subject 61", state.conversations.first().subject)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the unified inbox merges accounts and marks them`() = runTest {
        val first = accountWithInbox("a@example.test")
        val second = accountWithInbox("b@example.test")
        db.folderDao().upsert(listOf(folder(first, "Work", FolderRole.OTHER)))
        db.messageDao().upsert(
            listOf(
                message(first, 1, sentAt = 1_000),
                message(second, 1, sentAt = 2_000),
                message(first, 2, folderPath = "Work", sentAt = 3_000)
            )
        )
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Unified)
            val state = awaitState { it.loaded && it.conversations.size == 2 }
            assertEquals(listOf(second, first), state.conversations.map { it.accountId })
            assertEquals(setOf(first, second), state.markers.keys)
            assertNull(state.folderName)
            assertNull(state.accountEmail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with a single account the unified inbox has no markers`() = runTest {
        val id = accountWithInbox()
        conversations(id, 1)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Unified)
            val state = awaitState { it.loaded && it.conversations.size == 1 }
            assertTrue(state.markers.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh syncs the account of a folder and shows the refreshing state meanwhile`() =
        runTest {
            val id = accountWithInbox()
            val model = viewModel()
            refreshGate = CompletableDeferred()

            model.state.test {
                model.show(InboxScope.Folder(id, "INBOX"))
                awaitState { it.loaded }
                model.refresh()
                assertTrue(awaitState { it.refreshing }.refreshing)
                assertEquals(listOf<Long?>(id), refreshed)

                refreshGate?.complete(Unit)
                assertFalse(awaitState { !it.refreshing }.refreshing)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `refresh of the unified inbox syncs every account`() = runTest {
        accountWithInbox()
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Unified)
            awaitState { it.loaded }
            model.refresh()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf<Long?>(null), refreshed)
    }

    @Test
    fun `a refresh while another one runs is ignored, and refreshing works again after it`() =
        runTest {
            val id = accountWithInbox()
            val model = viewModel()
            refreshGate = CompletableDeferred()

            model.state.test {
                model.show(InboxScope.Folder(id, "INBOX"))
                awaitState { it.loaded }
                model.refresh()
                model.refresh()
                assertEquals(1, refreshed.size)
                refreshGate?.complete(Unit)
                awaitState { !it.refreshing && it.loaded }

                model.refresh()
                assertEquals(2, refreshed.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `refresh without a screen shown does nothing`() = runTest {
        viewModel().refresh()

        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun `the unread filter keeps only unread conversations`() = runTest {
        val id = accountWithInbox()
        db.messageDao().upsert(
            listOf(message(id, 1, seen = true), message(id, 2), message(id, 3, seen = true))
        )
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == 3 }
            model.setFilter(InboxFilter.UNREAD)
            val state = awaitState { it.filter == InboxFilter.UNREAD }
            assertEquals(listOf("Subject 2"), state.conversations.map { it.subject })
            model.setFilter(InboxFilter.ALL)
            assertEquals(3, awaitState { it.filter == InboxFilter.ALL }.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `when the filter hides everything the list is filtered out, not empty`() = runTest {
        val id = accountWithInbox()
        conversations(id, 3, seen = true)
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == 3 }
            model.setFilter(InboxFilter.UNREAD)
            val state = awaitState { it.filter == InboxFilter.UNREAD && it.conversations.isEmpty() }
            assertEquals(InboxEmpty.FILTERED_OUT, state.empty)
            assertEquals(3, state.loadedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a filter that hides most of a page keeps loading pages until it finds enough`() = runTest {
        val id = accountWithInbox()
        // 130 conversations, only the oldest one is unread: it is in the third page.
        db.messageDao().upsert(
            (1L..130L).map { message(id, it, seen = it != 1L) }
        )
        val model = viewModel()

        model.state.test {
            model.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == InboxViewModel.PAGE_SIZE }
            model.setFilter(InboxFilter.UNREAD)
            val state = awaitState {
                it.filter == InboxFilter.UNREAD && it.conversations.size == 1 && !it.hasMore
            }
            assertEquals("Subject 1", state.conversations.single().subject)
            assertEquals(130, state.loadedCount)
            assertNull(state.empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing the same scope keeps the pages and the scroll, another scope starts over`() =
        runTest {
            val id = accountWithInbox()
            db.folderDao().upsert(listOf(folder(id, "Work", FolderRole.OTHER)))
            conversations(id, 80)
            val model = viewModel()

            model.state.test {
                model.show(InboxScope.Folder(id, "INBOX"))
                awaitState { it.conversations.size == InboxViewModel.PAGE_SIZE }
                model.loadMore()
                awaitState { it.conversations.size == 80 }
                model.onScrolled(42, 17)

                model.show(InboxScope.Folder(id, "INBOX"))
                assertEquals(ScrollPosition(42, 17), model.savedScroll())
                assertEquals(2 * InboxViewModel.PAGE_SIZE, model.state.value.limit)

                model.show(InboxScope.Folder(id, "Work"))
                val state = awaitState { it.scope == InboxScope.Folder(id, "Work") && it.loaded }
                assertEquals(InboxViewModel.PAGE_SIZE, state.limit)
                assertEquals(ScrollPosition(), model.savedScroll())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `scope, pages, filter and scroll are restored from saved state`() = runTest {
        val id = accountWithInbox()
        conversations(id, 80)
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.state.test {
            first.show(InboxScope.Folder(id, "INBOX"))
            awaitState { it.conversations.size == InboxViewModel.PAGE_SIZE }
            first.loadMore()
            awaitState { it.conversations.size == 80 }
            first.setFilter(InboxFilter.UNREAD)
            first.onScrolled(30, 5)
            cancelAndIgnoreRemainingEvents()
        }

        val restored = viewModel(saved)

        assertEquals(ScrollPosition(30, 5), restored.savedScroll())
        restored.state.test {
            val state = awaitState { it.loaded }
            assertEquals(InboxScope.Folder(id, "INBOX"), state.scope)
            assertEquals(InboxFilter.UNREAD, state.filter)
            assertEquals(2 * InboxViewModel.PAGE_SIZE, state.limit)
            assertEquals(80, state.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `garbage in saved state is ignored`() = runTest {
        val saved = SavedStateHandle(mapOf("inbox.scope" to "nonsense", "inbox.filter" to "nope"))

        viewModel(saved).state.test {
            val state = awaitItem()
            assertFalse(state.loaded)
            assertEquals(InboxFilter.ALL, state.filter)
        }
    }

    private suspend fun ReceiveTurbine<InboxState>.awaitState(
        matches: (InboxState) -> Boolean
    ): InboxState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }
}
