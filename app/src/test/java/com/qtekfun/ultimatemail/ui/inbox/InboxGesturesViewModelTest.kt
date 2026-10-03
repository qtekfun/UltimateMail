// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.conversation.ConversationActions
import com.qtekfun.ultimatemail.domain.conversation.RecordingScheduler
import com.qtekfun.ultimatemail.domain.inbox.ConversationBulkActions
import com.qtekfun.ultimatemail.domain.inbox.InboxListing
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.domain.inbox.SwipeDirection
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Swipe, selection and the undo notices of the conversation list (T16). */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxGesturesViewModelTest {
    private var harness: EngineHarness? = null
    private val scheduler = RecordingScheduler()
    private val store = FakePreferenceStore()
    private val pickerRequests = mutableListOf<MovePickerRequest>()
    private lateinit var notices: NoticeCenter

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        notices = NoticeCenter(scheduler)
    }

    @AfterEach
    fun tearDown() {
        harness?.close()
        Dispatchers.resetMain()
    }

    private class Fixture(val h: EngineHarness, val accountId: Long) {
        val inboxScope = InboxScope.Folder(accountId, "INBOX")
    }

    /** INBOX with conversations t1..t3 (one message each), Archive and Trash. */
    private suspend fun TestScope.start(withArchive: Boolean = true): Fixture {
        val h = EngineHarness(this)
        harness = h
        val id = h.addAccount()
        h.db.folderDao().upsert(
            buildList {
                add(folder(id))
                if (withArchive) add(folder(id, "Archive", FolderRole.ARCHIVE))
                add(folder(id, "Trash", FolderRole.TRASH))
            }
        )
        h.db.folderDao().setSyncState(id, "INBOX", 1, 1, null)
        h.messages.upsert(
            (1L..3L).map { message(id, it, threadId = "t$it", seen = false, sentAt = it) }
        )
        return Fixture(h, id)
    }

    private fun viewModel(h: EngineHarness, saved: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(
            InboxListing(h.db),
            AccountListing(h.db),
            RefreshTrigger { },
            saved,
            SettingsRepository(store),
            RowActionRunner(
                ConversationBulkActions(
                    h.messages,
                    ConversationActions(h.messages, h.queue, h.marker, scheduler)
                ),
                notices,
                { request -> pickerRequests += request }
            )
        )

    private suspend fun ReceiveTurbine<InboxState>.awaitState(
        matches: (InboxState) -> Boolean
    ): InboxState {
        var state = awaitItem()
        while (!matches(state)) state = awaitItem()
        return state
    }

    private suspend fun <T : Any> eventually(block: suspend () -> T?): T =
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MILLIS) {
                var result = block()
                while (result == null) {
                    delay(POLL_MILLIS)
                    result = block()
                }
                result
            }
        }

    private suspend fun EngineHarness.queuedUids() =
        operations.all(accountId).map { it.type to it.uid }

    @Test
    fun `a long press selects the row and a tap on another adds it`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            assertFalse(loaded.selection.active)

            vm.toggleSelection(loaded.conversations[0])
            val one = awaitState { it.selection.active }
            assertEquals(listOf(loaded.conversations[0].key), one.selected.map { it.key })

            vm.toggleSelection(loaded.conversations[2])
            val two = awaitState { it.selection.count == 2 }
            assertEquals(2, two.selected.size)

            vm.toggleSelection(loaded.conversations[0])
            vm.toggleSelection(loaded.conversations[2])
            assertFalse(awaitState { !it.selection.active }.selection.active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `select all picks every conversation shown`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])

            vm.selectAll()

            assertEquals(3, awaitState { it.selection.count == 3 }.selected.size)
            vm.clearSelection()
            assertFalse(awaitState { !it.selection.active }.selection.active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the selection survives a new view model made from the same saved state`() = runTest {
        val f = start()
        val saved = SavedStateHandle()
        val first = viewModel(f.h, saved)
        var picked = ""
        first.state.test {
            first.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            first.toggleSelection(loaded.conversations[1])
            picked = loaded.conversations[1].key
            awaitState { it.selection.active }
            cancelAndIgnoreRemainingEvents()
        }

        viewModel(f.h, saved).state.test {
            val restored = awaitState { it.loaded && it.selection.active }
            assertEquals(setOf(picked), restored.selection.keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showing another folder clears the selection for good`() = runTest {
        val f = start()
        val saved = SavedStateHandle()
        val vm = viewModel(f.h, saved)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            awaitState { it.selection.active }

            vm.show(InboxScope.Folder(f.accountId, "Trash"))

            assertFalse(
                awaitState { it.scope?.key != f.inboxScope.key && !it.selection.active }
                    .selection.active
            )
            vm.show(f.inboxScope)
            assertFalse(awaitState { it.loaded && it.conversations.size == 3 }.selection.active)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(saved.get<ArrayList<String>>("inbox.selectionKeys").orEmpty().isEmpty())
    }

    @Test
    fun `a selected conversation that disappears is dropped from the selection`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            vm.toggleSelection(loaded.conversations[1])
            awaitState { it.selection.count == 2 }

            // The newest conversation is moved away by something else (another screen, a sync).
            f.h.queue.enqueue(
                com.qtekfun.ultimatemail.sync.queue.NewOperation(
                    f.accountId,
                    OperationType.MOVE,
                    "INBOX",
                    3,
                    "Archive"
                )
            )

            val after = awaitState { it.conversations.size == 2 && it.selection.count == 1 }
            assertEquals(listOf(loaded.conversations[1].key), after.selected.map { it.key })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `archiving the selection queues the moves, clears it and offers one undo`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            vm.toggleSelection(loaded.conversations[1])
            awaitState { it.selection.count == 2 }

            vm.applyToSelection(RowChange.ARCHIVE)

            val notice = eventually { notices.notice.value }
            assertEquals(NoticeKind.ARCHIVED, notice.kind)
            assertEquals(2, notice.count)
            assertTrue(notice.undoable)
            awaitState { !it.selection.active && it.conversations.size == 1 }
            assertEquals(
                listOf(OperationType.MOVE to 3L, OperationType.MOVE to 2L),
                f.h.queuedUids()
            )
            // The sync waits for the end of the undo window.
            assertTrue(scheduler.requests.isEmpty())

            notices.takeUndo(notice.id)!!.revert()
            awaitState { it.conversations.size == 3 }
            assertTrue(f.h.queuedUids().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the notice ending without undo sends the change`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            awaitState { it.selection.active }
            vm.applyToSelection(RowChange.MARK_READ)

            val notice = eventually { notices.notice.value }
            assertEquals(NoticeKind.MARKED_READ, notice.kind)
            notices.commit(notice.id)

            assertEquals(listOf<Long?>(f.accountId), scheduler.requests)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `swiping right archives and left deletes by default, and the row leaves`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }

            assertTrue(vm.onSwipe(loaded.conversations[0], SwipeDirection.RIGHT))
            assertEquals(NoticeKind.ARCHIVED, eventually { notices.notice.value }.kind)

            assertTrue(vm.onSwipe(loaded.conversations[1], SwipeDirection.LEFT))
            eventually { notices.notice.value?.takeIf { it.kind == NoticeKind.DELETED } }

            val queued = f.h.operations.all(f.accountId).associate { it.uid to it.payload }
            assertEquals(mapOf(3L to "Archive", 2L to "Trash"), queued)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the configured swipe actions are followed`() = runTest {
        val f = start()
        store.putString("swipe_right", SwipeAction.TOGGLE_STAR.name)
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState {
                it.loaded && it.conversations.size == 3 && it.swipe.right == SwipeAction.TOGGLE_STAR
            }

            val leaves = vm.onSwipe(loaded.conversations[0], SwipeDirection.RIGHT)

            assertFalse(leaves)
            assertEquals(NoticeKind.STARRED, eventually { notices.notice.value }.kind)
            assertTrue(f.h.messages.get(f.accountId, "INBOX", 3)!!.flagged)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an action that is none springs back and says nothing`() = runTest {
        val f = start()
        store.putString("swipe_right", SwipeAction.NONE.name)
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.swipe.right == SwipeAction.NONE }

            assertFalse(vm.onSwipe(loaded.conversations[0], SwipeDirection.RIGHT))

            assertNull(notices.notice.value)
            assertTrue(f.h.queuedUids().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `archive without an Archive folder springs back and tells the user`() = runTest {
        val f = start(withArchive = false)
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState {
                it.loaded && it.conversations.size == 3 && it.targets.folders.isNotEmpty()
            }

            assertFalse(vm.onSwipe(loaded.conversations[0], SwipeDirection.RIGHT))

            assertEquals(NoticeKind.NO_ARCHIVE_FOLDER, notices.notice.value?.kind)
            assertTrue(f.h.queuedUids().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `swiping does nothing while selecting`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            awaitState { it.selection.active }

            assertFalse(vm.onSwipe(loaded.conversations[1], SwipeDirection.RIGHT))

            assertNull(notices.notice.value)
            assertTrue(f.h.queuedUids().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a swipe that could not be applied asks for the row to be brought back`() = runTest {
        val f = start()
        // A message the server does not have (uid 0) cannot be moved.
        f.h.messages.upsert(listOf(message(f.accountId, 0, threadId = "local", sentAt = 99)))
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 4 }
            val local = loaded.conversations.first { it.threadId == "local" }
            vm.restoreRequests.test {
                assertTrue(vm.onSwipe(local, SwipeDirection.RIGHT))

                assertEquals(local.key, awaitItem())
            }
            assertNull(notices.notice.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `move opens the picker with every message of the selection and clears it`() = runTest {
        val f = start()
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState { it.loaded && it.conversations.size == 3 }
            vm.toggleSelection(loaded.conversations[0])
            vm.toggleSelection(loaded.conversations[1])
            awaitState { it.selection.count == 2 }

            vm.moveSelection()

            val request = eventually { pickerRequests.firstOrNull() }
            assertEquals(f.accountId, request.accountId)
            assertEquals(listOf(3L, 2L), request.messages.map { it.uid })
            awaitState { !it.selection.active }
            assertTrue(f.h.queuedUids().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `move from a swipe opens the picker for that conversation and springs back`() = runTest {
        val f = start()
        store.putString("swipe_left", SwipeAction.MOVE.name)
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(f.inboxScope)
            val loaded = awaitState {
                it.loaded && it.conversations.size == 3 && it.swipe.left == SwipeAction.MOVE
            }

            assertFalse(vm.onSwipe(loaded.conversations[2], SwipeDirection.LEFT))

            assertEquals(
                listOf(1L),
                eventually {
                    pickerRequests.firstOrNull()
                }.messages.map { it.uid }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `move is not offered for a selection across accounts`() = runTest {
        val f = start()
        val other = f.h.db.accountDao().insert(account("two@example.test"))
        f.h.db.folderDao().upsert(listOf(folder(other)))
        f.h.db.folderDao().setSyncState(other, "INBOX", 1, 1, null)
        f.h.messages.upsert(listOf(message(other, 1, threadId = "x", sentAt = 50)))
        val vm = viewModel(f.h)

        vm.state.test {
            vm.show(InboxScope.Unified)
            val loaded = awaitState { it.loaded && it.conversations.size == 4 }
            loaded.conversations.forEach { vm.toggleSelection(it) }
            val all = awaitState { it.selection.count == 4 }
            assertFalse(all.bulk.canMove)

            vm.moveSelection()

            assertTrue(pickerRequests.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
