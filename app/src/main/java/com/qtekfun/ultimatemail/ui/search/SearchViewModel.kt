// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.AccountMarker
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.search.DateFilter
import com.qtekfun.ultimatemail.domain.search.RecentSearches
import com.qtekfun.ultimatemail.domain.search.SearchFilters
import com.qtekfun.ultimatemail.domain.search.SearchListing
import com.qtekfun.ultimatemail.domain.search.SearchPage
import com.qtekfun.ultimatemail.domain.search.SearchQuery
import com.qtekfun.ultimatemail.domain.search.SearchQueryParser
import com.qtekfun.ultimatemail.domain.search.SearchScope
import com.qtekfun.ultimatemail.domain.search.ServerSearch
import com.qtekfun.ultimatemail.domain.search.ServerSearchFailure
import com.qtekfun.ultimatemail.domain.search.ServerSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a search is: nothing typed yet, working, showing hits, finding none, or broken. */
enum class SearchPhase { IDLE, SEARCHING, RESULTS, NONE_FOUND, ERROR }

/** The state of the "search on the server too" part of a search. */
sealed interface ServerPhase {
    data object Idle : ServerPhase

    data object Searching : ServerPhase

    /** [added] hits were new to the device; [incomplete] when some folder could not be searched. */
    data class Done(val added: Int, val incomplete: Boolean) : ServerPhase

    data class Failed(val reason: ServerSearchFailure) : ServerPhase
}

/**
 * What the search screen shows. [text] is what is in the field; the results are those of the
 * text as it was when the user paused ([query] is that text with the chips applied, and what
 * the hits highlight). [results] are the local hits; [serverResults] the hits found on the
 * server that the local ones do not already show.
 */
data class SearchState(
    val text: String = "",
    val scope: SearchScope = SearchScope.AllAccounts,
    val scopeChoices: List<SearchScope> = emptyList(),
    val filters: SearchFilters = SearchFilters.NONE,
    val query: SearchQuery = SearchQuery.EMPTY,
    val phase: SearchPhase = SearchPhase.IDLE,
    val results: List<ConversationItem> = emptyList(),
    val hasMore: Boolean = false,
    val server: ServerPhase = ServerPhase.Idle,
    val serverResults: List<ConversationItem> = emptyList(),
    /** The local results are few: the screen offers the search on the server by itself. */
    val offerServer: Boolean = false,
    val recent: List<String> = emptyList(),
    /** Markers by account id; only filled when hits of several accounts can mix. */
    val markers: Map<Long, AccountMarker> = emptyMap()
) {
    /** The search on the server needs something to look for and an account to ask. */
    val canSearchServer: Boolean
        get() = !query.isEmpty && phase != SearchPhase.IDLE && phase != SearchPhase.ERROR
}

/**
 * The search screen (RF-09): the text typed (debounced), the scope and the chips, the local
 * results as a query that re-runs when mail changes, the search on the server on demand, and the
 * recent searches. It keeps the text, scope, chips and the pages loaded in saved state, so
 * rotation (and the process being recreated) brings the same search back.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
@Suppress("TooManyFunctions", "LongParameterList") // What the screen does, and what it needs.
class SearchViewModel @Inject constructor(
    private val listing: SearchListing,
    private val serverSearch: ServerSearch,
    private val recentSearches: RecentSearches,
    accountListing: AccountListing,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val origin = MutableStateFlow(
        SearchScope.fromKey(savedState.get<String>(ORIGIN_KEY)) ?: SearchScope.AllAccounts
    )
    private val scope = MutableStateFlow(
        SearchScope.fromKey(savedState.get<String>(SCOPE_KEY)) ?: origin.value
    )
    private val typed = MutableStateFlow(savedState.get<String>(TEXT_KEY).orEmpty())

    /** The text the search runs for: [typed] once the user paused, or at once on submit. */
    private val committed = MutableStateFlow(typed.value)
    private val filters = MutableStateFlow(readFilters())
    private val limit = MutableStateFlow(savedState.get<Int>(LIMIT_KEY) ?: PAGE_SIZE)
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val serverPhase = MutableStateFlow<ServerPhase>(ServerPhase.Idle)
    private val serverIds = MutableStateFlow<List<Long>>(emptyList())
    private var serverJob: Job? = null

    private val queryFlow: Flow<SearchQuery> = combine(committed, filters) { text, chips ->
        chips.applyTo(SearchQueryParser.parse(text), today())
    }.distinctUntilChanged()

    private data class Local(
        val phase: SearchPhase,
        val query: SearchQuery,
        val page: SearchPage? = null
    )

    private val local: Flow<Local> = combine(queryFlow, scope) { query, where -> query to where }
        .flatMapLatest { (query, where) ->
            if (query.isEmpty) {
                flowOf(Local(SearchPhase.IDLE, query))
            } else {
                // Growing the limit restarts only the inner query: the rows on screen stay.
                limit.flatMapLatest { listing.observe(query, where, it, zone) }
                    .map { Local(phaseOf(it), query, it) }
                    .onStart { emit(Local(SearchPhase.SEARCHING, query)) }
                    .catch { emit(Local(SearchPhase.ERROR, query)) }
            }
        }

    private val serverItems: Flow<List<ConversationItem>> =
        combine(serverIds, queryFlow) { ids, query -> ids to query }
            .flatMapLatest { (ids, query) -> listing.observeByIds(ids, query) }

    private data class Inputs(
        val text: String,
        val scope: SearchScope,
        val origin: SearchScope,
        val filters: SearchFilters,
        val server: ServerPhase
    )

    private val inputs = combine(typed, scope, origin, filters, serverPhase, ::Inputs)

    val state: StateFlow<SearchState> = combine(
        inputs,
        local,
        serverItems,
        recent,
        accountListing.observe()
    ) { input, found, fromServer, recents, accounts ->
        build(input, found, fromServer, recents, accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SearchState())

    init {
        viewModelScope.launch {
            typed.debounce { if (it.isBlank()) 0L else DEBOUNCE_MILLIS }
                .collect { committed.value = it }
        }
        viewModelScope.launch {
            // What is searched changed: the pages loaded and the hits from the server are stale.
            combine(queryFlow, scope) { query, where -> query to where }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    resetServer()
                    setLimit(PAGE_SIZE)
                }
        }
        viewModelScope.launch { recent.value = withContext(io) { recentSearches.recent() } }
    }

    private fun build(
        input: Inputs,
        found: Local,
        fromServer: List<ConversationItem>,
        recents: List<String>,
        accounts: List<AccountSummary>
    ): SearchState {
        val items = found.page?.items.orEmpty()
        val shownKeys = items.mapTo(HashSet()) { it.key }
        val server = input.server
        val hasMore = found.page?.hasMore == true
        val few = found.phase == SearchPhase.NONE_FOUND ||
            (found.phase == SearchPhase.RESULTS && items.size < FEW_RESULTS)
        return SearchState(
            text = input.text,
            scope = input.scope,
            scopeChoices = choices(input.origin, accounts.size),
            filters = input.filters,
            query = found.query,
            phase = found.phase,
            results = items,
            hasMore = hasMore,
            server = server,
            serverResults = if (server is ServerPhase.Done) {
                fromServer.filter { it.key !in shownKeys }
            } else {
                emptyList()
            },
            offerServer = server == ServerPhase.Idle && !hasMore && few,
            recent = recents,
            markers = if (input.scope == SearchScope.AllAccounts && accounts.size > 1) {
                accounts.associate { it.id to AccountMarker.of(it) }
            } else {
                emptyMap()
            }
        )
    }

    /** The scopes the user can pick: where the search started from, wider and wider. */
    private fun choices(origin: SearchScope, accounts: Int): List<SearchScope> = buildList {
        if (origin is SearchScope.Folder) add(origin)
        origin.accountId?.let { add(SearchScope.Account(it)) }
        // With one account, "all accounts" would only repeat "this account".
        if (accounts != 1 || origin.accountId == null) add(SearchScope.AllAccounts)
    }.distinct()

    private fun phaseOf(page: SearchPage) =
        if (page.items.isEmpty() && !page.hasMore) SearchPhase.NONE_FOUND else SearchPhase.RESULTS

    /** Starts a new search from [from]: an empty field, no chips, the scope it was opened in. */
    fun startNew(from: SearchScope) {
        serverJob?.cancel()
        serverPhase.value = ServerPhase.Idle
        serverIds.value = emptyList()
        origin.value = from
        scope.value = from
        typed.value = ""
        committed.value = ""
        filters.value = SearchFilters.NONE
        setLimit(PAGE_SIZE)
        savedState[ORIGIN_KEY] = from.key
        savedState[SCOPE_KEY] = from.key
        savedState[TEXT_KEY] = ""
        saveFilters(SearchFilters.NONE)
    }

    fun onTextChange(text: String) {
        typed.value = text
        savedState[TEXT_KEY] = text
    }

    /** The keyboard's search key: search now and remember the search. */
    fun submit() {
        committed.value = typed.value
        remember(typed.value)
    }

    /** A recent search was tapped: put it in the field and run it. */
    fun useRecent(text: String) {
        onTextChange(text)
        submit()
    }

    fun setScope(choice: SearchScope) {
        scope.value = choice
        savedState[SCOPE_KEY] = choice.key
    }

    fun toggleUnread() = setFilters(filters.value.copy(unread = !filters.value.unread))

    fun toggleStarred() = setFilters(filters.value.copy(starred = !filters.value.starred))

    fun toggleAttachments() =
        setFilters(filters.value.copy(withAttachments = !filters.value.withAttachments))

    fun setDate(date: DateFilter) = setFilters(filters.value.copy(date = date))

    /** Near the end of the list: asks for another page, if the last one was full. */
    fun loadMore() {
        val current = state.value
        if (current.phase == SearchPhase.RESULTS && current.hasMore && limit.value < MAX_LIMIT) {
            setLimit(limit.value + PAGE_SIZE)
        }
    }

    /** A hit was opened: the search that found it is worth remembering. */
    fun onOpened() = remember(typed.value)

    /** Asks the servers in scope; the hits join the results when they arrive. */
    fun searchOnServer() {
        val current = state.value
        if (!current.canSearchServer || current.server == ServerPhase.Searching) return
        remember(typed.value)
        serverPhase.value = ServerPhase.Searching
        val query = current.query
        val where = current.scope
        serverJob?.cancel()
        serverJob = viewModelScope.launch {
            when (val result = serverSearch.search(query, where)) {
                is ServerSearchResult.Found -> {
                    serverIds.value = result.messageIds
                    serverPhase.value = ServerPhase.Done(result.added, result.incomplete)
                }

                is ServerSearchResult.Failed ->
                    serverPhase.value =
                        ServerPhase.Failed(result.reason)
            }
        }
    }

    /** Stops the search on the server that is running. */
    fun cancelServerSearch() {
        serverJob?.cancel()
        serverPhase.value = ServerPhase.Idle
    }

    fun removeRecent(text: String) {
        viewModelScope.launch {
            recent.value = withContext(io) {
                recentSearches.remove(text)
                recentSearches.recent()
            }
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            withContext(io) { recentSearches.clear() }
            recent.value = emptyList()
        }
    }

    private fun remember(text: String) {
        if (SearchQueryParser.parse(text).isEmpty) return
        viewModelScope.launch {
            recent.value = withContext(io) {
                recentSearches.record(text)
                recentSearches.recent()
            }
        }
    }

    private fun resetServer() {
        serverJob?.cancel()
        serverPhase.value = ServerPhase.Idle
        serverIds.value = emptyList()
    }

    private fun setFilters(value: SearchFilters) {
        filters.value = value
        saveFilters(value)
    }

    private fun setLimit(value: Int) {
        limit.value = value
        savedState[LIMIT_KEY] = value
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    private fun readFilters() = SearchFilters(
        unread = savedState.get<Boolean>(UNREAD_KEY) ?: false,
        starred = savedState.get<Boolean>(STARRED_KEY) ?: false,
        withAttachments = savedState.get<Boolean>(ATTACHMENTS_KEY) ?: false,
        date = DateFilter.fromKey(savedState.get<String>(DATE_KEY))
    )

    private fun saveFilters(value: SearchFilters) {
        savedState[UNREAD_KEY] = value.unread
        savedState[STARRED_KEY] = value.starred
        savedState[ATTACHMENTS_KEY] = value.withAttachments
        savedState[DATE_KEY] = value.date.key
    }

    companion object {
        /** Conversations loaded per page. */
        const val PAGE_SIZE = 50

        /** A search never loads more than this: a narrower search finds the rest. */
        const val MAX_LIMIT = 1_000

        /** The pause after the last key before the search runs. */
        const val DEBOUNCE_MILLIS = 250L

        /** Fewer local hits than this make the screen offer the search on the server. */
        const val FEW_RESULTS = 5

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val ORIGIN_KEY = "search.origin"
        private const val SCOPE_KEY = "search.scope"
        private const val TEXT_KEY = "search.text"
        private const val LIMIT_KEY = "search.limit"
        private const val UNREAD_KEY = "search.unread"
        private const val STARRED_KEY = "search.starred"
        private const val ATTACHMENTS_KEY = "search.attachments"
        private const val DATE_KEY = "search.date"
    }
}
