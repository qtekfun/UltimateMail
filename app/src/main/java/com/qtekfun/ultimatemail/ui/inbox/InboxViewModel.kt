// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.AccountMarker
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.InboxListing
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.inbox.InboxStatus
import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which conversations the list keeps. */
enum class InboxFilter { ALL, UNREAD }

/** Why a loaded list has nothing to show. */
enum class InboxEmpty {
    /** The first sync has not stored anything yet. */
    NOT_SYNCED,

    /** Synced, and there is simply no mail here. */
    NO_MESSAGES,

    /** There is mail, but the active filter hides all of it. */
    FILTERED_OUT
}

/** Where the list was scrolled to: the first visible item and how far into it. */
data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

/**
 * What the conversation list shows. [loaded] is false until Room answered for [scope];
 * [conversations] are already filtered, [loadedCount] counts them before filtering and [limit]
 * is the page size the query currently uses.
 */
data class InboxState(
    val scope: InboxScope? = null,
    val loaded: Boolean = false,
    val folderName: String? = null,
    val folderRole: FolderRole? = null,
    val accountEmail: String? = null,
    val synced: Boolean = false,
    val conversations: List<ConversationItem> = emptyList(),
    val loadedCount: Int = 0,
    val limit: Int = 0,
    val hasMore: Boolean = false,
    val filter: InboxFilter = InboxFilter.ALL,
    val refreshing: Boolean = false,
    /** Markers by account id; only filled when the unified inbox mixes several accounts. */
    val markers: Map<Long, AccountMarker> = emptyMap()
) {
    /** The empty state to show, or null while there is a list or more is still being loaded. */
    val empty: InboxEmpty?
        get() = when {
            !loaded || conversations.isNotEmpty() || hasMore -> null
            loadedCount > 0 -> InboxEmpty.FILTERED_OUT
            !synced -> InboxEmpty.NOT_SYNCED
            else -> InboxEmpty.NO_MESSAGES
        }
}

/**
 * The conversation list of a folder or of the unified inbox. It keeps the query reactive and
 * grows its limit page by page, and it remembers the scope, the loaded pages and the scroll
 * position in saved state so they survive rotation and coming back to the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val listing: InboxListing,
    accountListing: AccountListing,
    private val refreshTrigger: RefreshTrigger,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val scope = MutableStateFlow(InboxScope.fromKey(savedState.get<String>(SCOPE_KEY)))
    private val limit = MutableStateFlow(savedState.get<Int>(LIMIT_KEY) ?: PAGE_SIZE)
    private val filter = MutableStateFlow(
        savedState.get<String>(FILTER_KEY)?.let { name ->
            InboxFilter.entries.firstOrNull { it.name == name }
        } ?: InboxFilter.ALL
    )
    private val refreshing = MutableStateFlow(false)

    private data class Page(
        val scope: InboxScope,
        val limit: Int,
        val items: List<ConversationItem>
    )

    private val pages = combine(scope, limit) { s, l -> s to l }.flatMapLatest { (s, l) ->
        if (s == null) {
            flowOf(null)
        } else {
            listing.observe(s, l).map { Page(s, l, it) }
        }
    }

    private val statuses = scope.flatMapLatest { s ->
        if (s == null) flowOf(null) else listing.observeStatus(s).map { s to it }
    }

    private val accounts = accountListing.observe()

    val state: StateFlow<InboxState> = combine(
        pages,
        statuses,
        filter,
        refreshing,
        accounts
    ) { page, status, currentFilter, isRefreshing, allAccounts ->
        if (page == null || status == null || status.first != page.scope) {
            InboxState(scope = scope.value, filter = currentFilter, refreshing = isRefreshing)
        } else {
            build(page, status.second, currentFilter, isRefreshing, allAccounts)
        }
    }.onEach(::growWhileFilteredListIsShort).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        InboxState()
    )

    private fun build(
        page: Page,
        status: InboxStatus,
        currentFilter: InboxFilter,
        isRefreshing: Boolean,
        allAccounts: List<AccountSummary>
    ): InboxState {
        val shown = when (currentFilter) {
            InboxFilter.ALL -> page.items
            InboxFilter.UNREAD -> page.items.filter { it.unread }
        }
        val folder = page.scope as? InboxScope.Folder
        return InboxState(
            scope = page.scope,
            loaded = true,
            folderName = status.folderName,
            folderRole = status.folderRole,
            accountEmail = folder?.let { f ->
                allAccounts.firstOrNull { it.id == f.accountId }?.email
            },
            synced = status.synced,
            conversations = shown,
            loadedCount = page.items.size,
            limit = page.limit,
            hasMore = page.items.size >= page.limit && page.limit < MAX_LIMIT,
            filter = currentFilter,
            refreshing = isRefreshing,
            markers = if (page.scope == InboxScope.Unified && allAccounts.size > 1) {
                allAccounts.associate { it.id to AccountMarker.of(it) }
            } else {
                emptyMap()
            }
        )
    }

    /**
     * Shows [newScope]. Asking for the scope already shown (after rotation, or coming back from
     * another screen) keeps its pages and scroll position; a different one starts from the top.
     */
    fun show(newScope: InboxScope) {
        if (scope.value == newScope) return
        limit.value = PAGE_SIZE
        filter.value = InboxFilter.ALL
        scope.value = newScope
        savedState[SCOPE_KEY] = newScope.key
        savedState[LIMIT_KEY] = PAGE_SIZE
        savedState[FILTER_KEY] = InboxFilter.ALL.name
        savedState[SCROLL_INDEX_KEY] = 0
        savedState[SCROLL_OFFSET_KEY] = 0
    }

    /** Where the list was last scrolled to, to start from when its screen is created again. */
    fun savedScroll() = ScrollPosition(
        savedState.get<Int>(SCROLL_INDEX_KEY) ?: 0,
        savedState.get<Int>(SCROLL_OFFSET_KEY) ?: 0
    )

    /** Remembers where the list is scrolled to. */
    fun onScrolled(index: Int, offset: Int) {
        savedState[SCROLL_INDEX_KEY] = index
        savedState[SCROLL_OFFSET_KEY] = offset
    }

    /** Called when the user nears the end of the list: loads one more page, if there is one. */
    fun loadMore() {
        val current = state.value
        if (current.loaded && current.hasMore && current.limit == limit.value) {
            setLimit(limit.value + PAGE_SIZE)
        }
    }

    fun setFilter(newFilter: InboxFilter) {
        filter.value = newFilter
        savedState[FILTER_KEY] = newFilter.name
    }

    /** Pull-to-refresh: asks for a sync and shows the refreshing state until it is done. */
    fun refresh() {
        val current = scope.value ?: return
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                refreshTrigger.refresh((current as? InboxScope.Folder)?.accountId)
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun setLimit(value: Int) {
        limit.value = value
        savedState[LIMIT_KEY] = value
    }

    /** A filter can hide most of a page, so keep paging until it fills or the mail runs out. */
    private fun growWhileFilteredListIsShort(current: InboxState) {
        val filteredAndMoreToLoad =
            current.loaded && current.filter != InboxFilter.ALL && current.hasMore
        val pageArrived = current.limit == limit.value
        if (filteredAndMoreToLoad && pageArrived && current.conversations.size < PAGE_SIZE) {
            setLimit(limit.value + PAGE_SIZE)
        }
    }

    companion object {
        /** Conversations loaded per page. */
        const val PAGE_SIZE = 50

        /** A list never loads more than this in one query; older mail is reached by search. */
        const val MAX_LIMIT = 2_000

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SCOPE_KEY = "inbox.scope"
        private const val LIMIT_KEY = "inbox.limit"
        private const val FILTER_KEY = "inbox.filter"
        private const val SCROLL_INDEX_KEY = "inbox.scrollIndex"
        private const val SCROLL_OFFSET_KEY = "inbox.scrollOffset"
    }
}
