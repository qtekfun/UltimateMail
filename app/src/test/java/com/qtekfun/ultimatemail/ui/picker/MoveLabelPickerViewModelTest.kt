// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.folder.FolderListing
import com.qtekfun.ultimatemail.domain.picker.CheckState
import com.qtekfun.ultimatemail.domain.picker.InMemoryRecentDestinations
import com.qtekfun.ultimatemail.domain.picker.LocalMoveApplier
import com.qtekfun.ultimatemail.domain.picker.MessageRef
import com.qtekfun.ultimatemail.domain.picker.MoveLabelActions
import com.qtekfun.ultimatemail.domain.picker.PickerListItem
import com.qtekfun.ultimatemail.domain.picker.PickerMode
import com.qtekfun.ultimatemail.domain.picker.PickerOutcome
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.domain.picker.PickerSource
import com.qtekfun.ultimatemail.sync.engine.PendingSyncMarker
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class MoveLabelPickerViewModelTest {
    private lateinit var db: UltimateMailDatabase
    private val dispatcher = UnconfinedTestDispatcher()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val recents = InMemoryRecentDestinations()
    private var imap = 0L
    private var gmail = 0L

    @BeforeEach
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        db = inMemoryDatabase()
        imap = db.accountDao().insert(account("imap@example.test"))
        db.folderDao().upsert(
            listOf(
                FolderEntity(imap, "INBOX", "INBOX", FolderRole.INBOX),
                FolderEntity(imap, "Sent", "Sent", FolderRole.SENT),
                FolderEntity(imap, "Archive", "Archive", FolderRole.ARCHIVE),
                FolderEntity(imap, "Work/Invoices 2025/Facturas", "Facturas"),
                FolderEntity(imap, "Personal/Niños", "Niños")
            )
        )
        db.messageDao().upsert(listOf(message(imap, 1), message(imap, 2)))
        gmail = db.accountDao().insert(account("gmail@example.test"))
        db.folderDao().upsert(
            listOf(
                FolderEntity(gmail, "INBOX", "INBOX", FolderRole.INBOX),
                FolderEntity(gmail, "Work", "Work", isLabel = true),
                FolderEntity(gmail, "Personal", "Personal", isLabel = true)
            )
        )
        db.messageDao().upsert(
            listOf(
                message(gmail, 1, labels = listOf("\\Inbox", "Work")),
                message(gmail, 2, labels = listOf("\\Inbox"))
            )
        )
    }

    @AfterEach
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private val created = mutableListOf<MoveLabelPickerViewModel>()

    private fun viewModel(
        request: PickerRequest,
        roleNames: Map<FolderRole, String> = emptyMap()
    ): MoveLabelPickerViewModel {
        val queue = OperationQueue(
            db.pendingOperationDao(),
            mockk(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            dispatcher
        )
        val source = PickerSource(db, FolderListing(db))
        val actions = MoveLabelActions(
            queue,
            LocalMoveApplier(
                db.messageDao(),
                db.pendingOperationDao(),
                PendingSyncMarker(db.messageDao(), db.pendingOperationDao())
            ),
            recents,
            scheduler,
            source,
            dispatcher
        )
        return MoveLabelPickerViewModel(request, source, actions, recents, dispatcher, roleNames)
            .also { created += it }
    }

    private fun imapRequest() =
        PickerRequest(imap, listOf(MessageRef("INBOX", 1), MessageRef("INBOX", 2)))

    private fun gmailRequest() =
        PickerRequest(gmail, listOf(MessageRef("INBOX", 1), MessageRef("INBOX", 2)))

    private suspend fun MoveLabelPickerViewModel.ready() = state.first { !it.loading }

    private suspend fun MoveLabelPickerViewModel.finished() = state.first {
        it.finished != null
    }.finished

    private fun PickerState.entries() = listing.items.filterIsInstance<PickerListItem.Entry>()

    private fun PickerState.paths() = entries().map { it.folder.path }

    @Test
    fun `an imap account gets the folder picker with the tree`() = runTest {
        val vm = viewModel(imapRequest())

        val state = vm.ready()

        assertEquals(PickerMode.FOLDERS, state.mode)
        assertEquals(
            listOf(
                "INBOX",
                "Sent",
                "Archive",
                "Personal/Niños",
                "Work/Invoices 2025/Facturas"
            ),
            state.paths()
        )
        assertFalse(state.canApply)
        assertFalse(state.entries().first { it.folder.path == "INBOX" }.folder.enabled)
    }

    @Test
    fun `a gmail account gets the label picker`() = runTest {
        val vm = viewModel(gmailRequest())

        val state = vm.ready()

        assertEquals(PickerMode.LABELS, state.mode)
        assertEquals(listOf("INBOX", "Personal", "Work"), state.paths())
    }

    @Test
    fun `typing filters live, ignoring case and accents, and matching any part of the path`() =
        runTest {
            val vm = viewModel(imapRequest())
            vm.ready()

            vm.onQueryChange("FACT")
            assertEquals(listOf("Work/Invoices 2025/Facturas"), vm.state.value.paths())

            vm.onQueryChange("ninos")
            assertEquals(listOf("Personal/Niños"), vm.state.value.paths())

            vm.onQueryChange("work")
            assertEquals(listOf("Work/Invoices 2025/Facturas"), vm.state.value.paths())
            assertEquals("work", vm.state.value.query)
        }

    @Test
    fun `a search with no result says there are no matches and clearing it brings the list back`() =
        runTest {
            val vm = viewModel(imapRequest())
            vm.ready()

            vm.onQueryChange("zzzz")
            assertTrue(vm.state.value.listing.noMatches)

            vm.onQueryChange("")
            assertFalse(vm.state.value.listing.noMatches)
            assertEquals(5, vm.state.value.paths().size)
        }

    @Test
    fun `special folders are found by their name in the user's language`() = runTest {
        val vm = viewModel(imapRequest(), mapOf(FolderRole.SENT to "Enviados"))
        vm.ready()

        vm.onQueryChange("enviad")

        assertEquals(listOf("Sent"), vm.state.value.paths())
        assertEquals("Enviados", vm.state.value.entries().single().folder.name)
    }

    @Test
    fun `recent destinations are listed first`() = runTest {
        recents.record(imap, listOf("Work/Invoices 2025/Facturas", "Archive"))

        val state = viewModel(imapRequest()).ready()

        val recent = state.entries().filter { it.recent }.map { it.folder.path }
        assertEquals(listOf("Archive", "Work/Invoices 2025/Facturas"), recent)
        assertTrue(state.listing.items.first() is PickerListItem.Section)
    }

    @Test
    fun `a folder used more often wins between equal matches`() = runTest {
        recents.record(imap, listOf("Work/Invoices 2025/Facturas", "Work/Invoices 2025/Facturas"))
        recents.record(imap, listOf("Personal/Niños"))
        val vm = viewModel(imapRequest())
        vm.ready()

        vm.onQueryChange("s")

        // Both end in "s" with no better match; the shorter path would win if usage did not.
        assertEquals(
            listOf("Work/Invoices 2025/Facturas", "Personal/Niños"),
            vm.state.value.paths().filter { it.contains("/") }
        )
    }

    @Test
    fun `tapping a folder moves the messages there and finishes with an undoable result`() =
        runTest {
            val vm = viewModel(imapRequest())
            val state = vm.ready()

            vm.onDestinationClick(state.entries().first { it.folder.path == "Archive" }.folder)

            val finished = vm.finished() as PickerFinish.Applied
            assertEquals(PickerOutcome.Moved("Archive", "Archive"), finished.result.outcome)
            assertEquals(2, finished.result.messageCount)
            assertEquals(
                listOf(OperationType.MOVE, OperationType.MOVE),
                db.pendingOperationDao().all(imap).map { it.type }
            )
            assertEquals(listOf(imap to listOf("Archive")), recents.recorded)
            verify { scheduler.requestSync(imap, true) }
        }

    @Test
    fun `the current folder cannot be chosen`() = runTest {
        val vm = viewModel(imapRequest())
        val state = vm.ready()

        vm.onDestinationClick(state.entries().first { it.folder.path == "INBOX" }.folder)

        assertNull(vm.state.value.finished)
        assertTrue(db.pendingOperationDao().all(imap).isEmpty())
    }

    @Test
    fun `label checkboxes start from the labels of the messages`() = runTest {
        val state = viewModel(gmailRequest()).ready()

        val states = state.entries().associate { it.folder.path to it.state }
        assertEquals(CheckState.CHECKED, states["INBOX"])
        assertEquals(CheckState.PARTIAL, states["Work"])
        assertEquals(CheckState.UNCHECKED, states["Personal"])
    }

    @Test
    fun `tapping labels toggles them and enables apply, and nothing is queued until apply`() =
        runTest {
            val vm = viewModel(gmailRequest())
            val state = vm.ready()

            vm.onDestinationClick(state.entries().first { it.folder.path == "Work" }.folder)
            vm.onDestinationClick(state.entries().first { it.folder.path == "Personal" }.folder)

            val now = vm.state.value
            assertEquals(CheckState.CHECKED, now.entries().first { it.folder.path == "Work" }.state)
            assertEquals(
                CheckState.CHECKED,
                now.entries().first {
                    it.folder.path == "Personal"
                }.state
            )
            assertTrue(now.canApply)
            assertNull(now.finished)
            assertTrue(db.pendingOperationDao().all(gmail).isEmpty())
        }

    @Test
    fun `applying label changes queues the operations and finishes`() = runTest {
        val vm = viewModel(gmailRequest())
        val state = vm.ready()
        vm.onDestinationClick(state.entries().first { it.folder.path == "Work" }.folder)
        vm.onDestinationClick(state.entries().first { it.folder.path == "INBOX" }.folder)

        vm.apply()

        val finished = vm.finished() as PickerFinish.Applied
        assertEquals(
            listOf(
                OperationType.REMOVE_LABEL to (1L to "\\Inbox"),
                OperationType.ADD_LABEL to (2L to "Work"),
                OperationType.REMOVE_LABEL to (2L to "\\Inbox")
            ),
            db.pendingOperationDao().all(gmail).map { it.type to (it.uid to it.payload) }
        )
        assertEquals(
            PickerOutcome.LabelsChanged(listOf("Work"), listOf("INBOX")),
            finished.result.outcome
        )
        assertEquals(listOf(gmail to listOf("Work")), recents.recorded)
        assertEquals(listOf("Work"), db.messageDao().get(gmail, "INBOX", 2)!!.labels)
    }

    @Test
    fun `applying without changes just closes`() = runTest {
        val vm = viewModel(gmailRequest())
        vm.ready()

        vm.apply()

        assertEquals(PickerFinish.Unchanged, vm.finished())
        assertTrue(db.pendingOperationDao().all(gmail).isEmpty())
    }

    @Test
    fun `apply does nothing in folder mode`() = runTest {
        val vm = viewModel(imapRequest())
        vm.ready()

        vm.apply()

        assertNull(vm.state.value.finished)
    }

    @Test
    fun `an account that is gone makes the picker unavailable`() = runTest {
        val vm = viewModel(PickerRequest(999, listOf(MessageRef("INBOX", 1))))

        val state = vm.state.first { it.unavailable }

        assertFalse(state.loading)
        assertTrue(state.listing.items.isEmpty())
    }

    @Test
    fun `taps before the folders are loaded are ignored`() = runTest {
        val vm = viewModel(PickerRequest(999, listOf(MessageRef("INBOX", 1))))

        vm.apply()

        assertNull(vm.state.value.finished)
    }
}
