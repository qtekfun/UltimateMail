// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.search

import androidx.lifecycle.SavedStateHandle
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.search.DateFilter
import com.qtekfun.ultimatemail.domain.search.InMemoryRecentSearches
import com.qtekfun.ultimatemail.domain.search.SearchListing
import com.qtekfun.ultimatemail.domain.search.SearchQuery
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.domain.search.ServerSearch
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure
import com.qtekfun.ultimatemail.domain.search.ServerSearchResult
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private class FakeServer : ServerSearch {
        var result: ServerSearchResult = ServerSearchResult.Found(emptyList(), 0, false)
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Pair<SearchQuery, SearchScope>>()

        override suspend fun search(query: SearchQuery, scope: SearchScope): ServerSearchResult {
            calls += query to scope
            gate?.await()
            return result
        }
    }

    private lateinit var db: UltimateMailDatabase
    private val recent = InMemoryRecentSearches()
    private val server = FakeServer()
    private var ana = 0L
    private var bea = 0L
    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = inMemoryDatabase()
        ana = db.accountDao().insert(account("ana@example.test"))
        db.folderDao().upsert(listOf(folder(ana)))
    }

    @AfterEach
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        saved: SavedStateHandle = SavedStateHandle(),
        listing: SearchListing = SearchListing(db)
    ): SearchViewModel {
        val model = SearchViewModel(
            listing,
            server,
            recent,
            AccountListing(db),
            clock,
            Dispatchers.Unconfined,
            saved
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        return model
    }

    private suspend fun insert(count: Int, subject: String = "alpha") {
        db.messageDao().upsert(
            (1L..count).map { message(ana, it, subject = "$subject $it", sentAt = it * 1000) }
        )
    }

    private fun TestScope.type(model: SearchViewModel, text: String) {
        model.onTextChange(text)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS + 1)
        runCurrent()
    }

    @Test
    fun `before anything is typed the screen is idle and offers the recent searches`() = runTest {
        recent.record("invoice")
        val model = viewModel()
        runCurrent()

        val state = model.state.value

        assertEquals(SearchPhase.IDLE, state.phase)
        assertEquals(listOf("invoice"), state.recent)
        assertTrue(state.results.isEmpty())
        assertFalse(state.canSearchServer)
    }

    @Test
    fun `the search waits for a pause in the typing`() = runTest {
        insert(3)
        val model = viewModel()
        model.onTextChange("alp")

        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS - 10)
        runCurrent()
        assertEquals(SearchPhase.IDLE, model.state.value.phase)
        assertEquals("alp", model.state.value.text)

        advanceTimeBy(20)
        runCurrent()
        assertEquals(SearchPhase.RESULTS, model.state.value.phase)
        assertEquals(3, model.state.value.results.size)
    }

    @Test
    fun `each key press restarts the wait and only the last text is searched`() = runTest {
        insert(3)
        val model = viewModel()

        model.onTextChange("a")
        advanceTimeBy(100)
        model.onTextChange("al")
        advanceTimeBy(100)
        model.onTextChange("alpha 2")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertEquals(listOf("alpha 2"), model.state.value.results.map { it.subject })
    }

    @Test
    fun `submitting searches at once and remembers the search`() = runTest {
        insert(2)
        val model = viewModel()
        model.onTextChange("alpha")

        model.submit()
        runCurrent()

        assertEquals(SearchPhase.RESULTS, model.state.value.phase)
        assertEquals(listOf("alpha"), recent.recent())
    }

    @Test
    fun `clearing the field goes back to idle without waiting`() = runTest {
        insert(2)
        val model = viewModel()
        type(model, "alpha")

        model.onTextChange("")
        runCurrent()

        assertEquals(SearchPhase.IDLE, model.state.value.phase)
    }

    @Test
    fun `typing alone never remembers anything`() = runTest {
        insert(2)
        val model = viewModel()

        type(model, "alpha")

        assertTrue(recent.recent().isEmpty())
    }

    @Test
    fun `opening a hit remembers the search, and a blank one is never stored`() = runTest {
        val model = viewModel()
        model.onTextChange("  ")
        model.onOpened()
        runCurrent()
        assertTrue(recent.recent().isEmpty())

        model.onTextChange("from:ana")
        model.onOpened()
        runCurrent()
        assertEquals(listOf("from:ana"), recent.recent())
    }

    @Test
    fun `nothing found is its own state`() = runTest {
        insert(2)
        val model = viewModel()

        type(model, "zebra")

        assertEquals(SearchPhase.NONE_FOUND, model.state.value.phase)
        assertTrue(model.state.value.offerServer)
    }

    @Test
    fun `a failing query is an error state, not a crash`() = runTest {
        val listing = mockk<SearchListing>()
        every { listing.observe(any(), any(), any(), any()) } returns
            flow { error("broken") }
        every { listing.observeByIds(any(), any()) } returns flowOf(emptyList())
        val model = viewModel(listing = listing)

        type(model, "alpha")

        assertEquals(SearchPhase.ERROR, model.state.value.phase)
        assertFalse(model.state.value.canSearchServer)
    }

    @Test
    fun `filters work without any text and show in the query`() = runTest {
        db.messageDao().upsert(
            listOf(
                message(ana, 1, seen = false),
                message(ana, 2, seen = true)
            )
        )
        val model = viewModel()

        model.toggleUnread()
        runCurrent()

        assertEquals(listOf("Subject 1"), model.state.value.results.map { it.subject })
        assertEquals(true, model.state.value.query.unread)
        model.toggleUnread()
        runCurrent()
        assertEquals(SearchPhase.IDLE, model.state.value.phase)
    }

    @Test
    fun `the other chips and the date are applied with today's date`() = runTest {
        val recentDay = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
        val oldDay = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        db.messageDao().upsert(
            listOf(
                message(ana, 1, sentAt = recentDay, flagged = true, hasAttachments = true),
                message(ana, 2, sentAt = oldDay, flagged = true, hasAttachments = true),
                message(ana, 3, sentAt = recentDay)
            )
        )
        val model = viewModel()

        model.toggleStarred()
        model.toggleAttachments()
        model.setDate(DateFilter.Last7Days)
        runCurrent()

        assertEquals(listOf("Subject 1"), model.state.value.results.map { it.subject })
        assertTrue(model.state.value.filters.starred)
    }

    @Test
    fun `scroll growth loads the next page while the rows stay`() = runTest {
        insert(120)
        val model = viewModel()
        type(model, "alpha")
        assertEquals(SearchViewModel.PAGE_SIZE, model.state.value.results.size)
        assertTrue(model.state.value.hasMore)

        model.loadMore()
        runCurrent()
        assertEquals(2 * SearchViewModel.PAGE_SIZE, model.state.value.results.size)
        assertEquals(SearchPhase.RESULTS, model.state.value.phase)

        model.loadMore()
        runCurrent()
        assertEquals(120, model.state.value.results.size)
        assertFalse(model.state.value.hasMore)
        model.loadMore()
        runCurrent()
        assertEquals(120, model.state.value.results.size)
    }

    @Test
    fun `a new search starts again from the first page`() = runTest {
        insert(120)
        val model = viewModel()
        type(model, "alpha")
        model.loadMore()
        runCurrent()

        type(model, "alpha 1")

        assertTrue(model.state.value.results.size < SearchViewModel.PAGE_SIZE)
    }

    @Test
    fun `text, scope, chips and pages survive rotation`() = runTest {
        val farFuture = DateFilter.Custom(null, LocalDate.of(2100, 1, 1))
        insert(120)
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        first.startNew(SearchScope.Folder(ana, "INBOX"))
        first.setScope(SearchScope.Account(ana))
        first.toggleUnread()
        first.setDate(farFuture)
        type(first, "alpha")
        first.loadMore()
        runCurrent()

        val second = viewModel(saved)
        runCurrent()

        val state = second.state.value
        assertEquals("alpha", state.text)
        assertEquals(SearchScope.Account(ana), state.scope)
        assertTrue(state.filters.unread)
        assertEquals(farFuture, state.filters.date)
        assertEquals(SearchPhase.RESULTS, state.phase)
        assertEquals(2 * SearchViewModel.PAGE_SIZE, state.results.size)
        assertEquals(
            listOf(
                SearchScope.Folder(ana, "INBOX"),
                SearchScope.Account(ana)
            ),
            state.scopeChoices
        )
    }

    @Test
    fun `starting a new search clears the old one`() = runTest {
        insert(2)
        val model = viewModel()
        model.toggleStarred()
        type(model, "alpha")

        model.startNew(SearchScope.AllAccounts)
        runCurrent()

        assertEquals("", model.state.value.text)
        assertEquals(SearchPhase.IDLE, model.state.value.phase)
        assertTrue(model.state.value.filters.isEmpty)
    }

    @Test
    fun `the scopes offered widen from where the search started`() = runTest {
        bea = db.accountDao().insert(account("bea@example.test"))
        val model = viewModel()

        model.startNew(SearchScope.Folder(ana, "INBOX"))
        runCurrent()
        assertEquals(
            listOf(
                SearchScope.Folder(ana, "INBOX"),
                SearchScope.Account(ana),
                SearchScope.AllAccounts
            ),
            model.state.value.scopeChoices
        )

        model.startNew(SearchScope.AllAccounts)
        runCurrent()
        assertEquals(listOf(SearchScope.AllAccounts), model.state.value.scopeChoices)
        assertEquals(SearchScope.AllAccounts, model.state.value.scope)
    }

    @Test
    fun `with one account there is no all accounts choice and no markers`() = runTest {
        val model = viewModel()

        model.startNew(SearchScope.Folder(ana, "INBOX"))
        runCurrent()

        assertEquals(
            listOf(SearchScope.Folder(ana, "INBOX"), SearchScope.Account(ana)),
            model.state.value.scopeChoices
        )
        assertTrue(model.state.value.markers.isEmpty())
    }

    @Test
    fun `hits of several accounts carry markers in an all accounts search`() = runTest {
        bea = db.accountDao().insert(account("bea@example.test"))
        val model = viewModel()

        model.startNew(SearchScope.AllAccounts)
        runCurrent()

        assertEquals(setOf(ana, bea), model.state.value.markers.keys)
    }

    @Test
    fun `changing the scope searches again there`() = runTest {
        bea = db.accountDao().insert(account("bea@example.test"))
        db.folderDao().upsert(listOf(folder(bea)))
        db.messageDao().upsert(
            listOf(message(ana, 1, subject = "alpha"), message(bea, 1, subject = "alpha"))
        )
        val model = viewModel()
        model.startNew(SearchScope.AllAccounts)
        type(model, "alpha")
        assertEquals(2, model.state.value.results.size)

        model.setScope(SearchScope.Account(bea))
        runCurrent()

        assertEquals(listOf(bea), model.state.value.results.map { it.accountId })
    }

    @Test
    fun `few local results offer the search on the server and many do not`() = runTest {
        insert(2)
        val model = viewModel()
        type(model, "alpha")
        assertTrue(model.state.value.offerServer)

        insert(SearchViewModel.FEW_RESULTS)
        runCurrent()

        assertFalse(model.state.value.offerServer)
        assertTrue(model.state.value.canSearchServer)
    }

    @Test
    fun `the search on the server runs in the scope of the search and shows its hits`() = runTest {
        insert(1)
        val remote = db.messageDao().also {
            it.upsert(listOf(message(ana, 50, subject = "only on the server")))
        }.get(ana, "INBOX", 50)!!.id
        server.result = ServerSearchResult.Found(listOf(remote), added = 1, incomplete = false)
        val model = viewModel()
        model.startNew(SearchScope.Account(ana))
        type(model, "alpha")

        model.searchOnServer()
        runCurrent()

        val state = model.state.value
        assertEquals(ServerPhase.Done(added = 1, incomplete = false), state.server)
        assertEquals(listOf("only on the server"), state.serverResults.map { it.subject })
        assertEquals(1, server.calls.size)
        assertEquals(SearchScope.Account(ana), server.calls.single().second)
        assertEquals("alpha", server.calls.single().first.terms.single().text)
        assertEquals(listOf("alpha"), recent.recent())
        assertFalse(state.offerServer)
    }

    @Test
    fun `a server hit already shown locally is not listed twice`() = runTest {
        insert(1)
        val local = db.messageDao().get(ana, "INBOX", 1)!!.id
        server.result = ServerSearchResult.Found(listOf(local), added = 0, incomplete = true)
        val model = viewModel()
        type(model, "alpha")

        model.searchOnServer()
        runCurrent()

        assertEquals(ServerPhase.Done(0, true), model.state.value.server)
        assertTrue(model.state.value.serverResults.isEmpty())
    }

    @Test
    fun `while the server answers the state says so and cancelling goes back`() = runTest {
        insert(1)
        server.gate = CompletableDeferred()
        val model = viewModel()
        type(model, "alpha")

        model.searchOnServer()
        runCurrent()
        assertEquals(ServerPhase.Searching, model.state.value.server)
        model.searchOnServer()
        runCurrent()
        assertEquals(1, server.calls.size, "a second request while one runs is ignored")

        model.cancelServerSearch()
        runCurrent()
        assertEquals(ServerPhase.Idle, model.state.value.server)
        server.gate!!.complete(Unit)
        runCurrent()
        assertEquals(ServerPhase.Idle, model.state.value.server)
    }

    @Test
    fun `a failure of the server is shown with its reason and can be retried`() = runTest {
        insert(1)
        server.result = ServerSearchResult.Failed(ServerSearchFailure.OFFLINE)
        val model = viewModel()
        type(model, "alpha")

        model.searchOnServer()
        runCurrent()
        assertEquals(ServerPhase.Failed(ServerSearchFailure.OFFLINE), model.state.value.server)

        server.result = ServerSearchResult.Found(emptyList(), 0, false)
        model.searchOnServer()
        runCurrent()
        assertEquals(ServerPhase.Done(0, false), model.state.value.server)
        assertEquals(2, server.calls.size)
    }

    @Test
    fun `typing something else forgets the hits from the server and stops its search`() = runTest {
        insert(1)
        server.gate = CompletableDeferred()
        val model = viewModel()
        type(model, "alpha")
        model.searchOnServer()
        runCurrent()

        type(model, "alpha 1")

        assertEquals(ServerPhase.Idle, model.state.value.server)
        server.gate!!.complete(Unit)
        runCurrent()
        assertEquals(ServerPhase.Idle, model.state.value.server)
    }

    @Test
    fun `there is nothing to ask the server before something is searched`() = runTest {
        val model = viewModel()

        model.searchOnServer()
        runCurrent()

        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `recent searches can be repeated, removed and cleared`() = runTest {
        insert(2)
        recent.record("one")
        recent.record("two")
        val model = viewModel()
        runCurrent()
        assertEquals(listOf("two", "one"), model.state.value.recent)

        model.useRecent("one")
        runCurrent()
        assertEquals("one", model.state.value.text)
        assertEquals(listOf("one", "two"), model.state.value.recent)

        model.removeRecent("two")
        runCurrent()
        assertEquals(listOf("one"), model.state.value.recent)

        model.clearRecent()
        runCurrent()
        assertTrue(model.state.value.recent.isEmpty())
        assertTrue(recent.recent().isEmpty())
    }
}
