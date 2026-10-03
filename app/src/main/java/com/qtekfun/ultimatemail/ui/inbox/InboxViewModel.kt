// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.SwipeActions
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.AccountMarker
import com.qtekfun.ultimatemail.domain.inbox.BulkAvailability
import com.qtekfun.ultimatemail.domain.inbox.ConversationItem
import com.qtekfun.ultimatemail.domain.inbox.InboxListing
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.inbox.InboxStatus
import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import com.qtekfun.ultimatemail.domain.inbox.RowChange
import com.qtekfun.ultimatemail.domain.inbox.RowTargets
import com.qtekfun.ultimatemail.domain.inbox.Selection
import com.qtekfun.ultimatemail.domain.inbox.SwipeBlock
import com.qtekfun.ultimatemail.domain.inbox.SwipeDecision
import com.qtekfun.ultimatemail.domain.inbox.SwipeDirection
import com.qtekfun.ultimatemail.domain.inbox.SwipePlanner
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /** The account is set not to sync this folder, so nothing will ever arrive. */
    NOT_ENABLED,

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
    val syncDisabled: Boolean = false,
    val conversations: List<ConversationItem> = emptyList(),
    val loadedCount: Int = 0,
    val limit: Int = 0,
    val hasMore: Boolean = false,
    val filter: InboxFilter = InboxFilter.ALL,
    val refreshing: Boolean = false,
    /** Markers by account id; only filled when the unified inbox mixes several accounts. */
    val markers: Map<Long, AccountMarker> = emptyMap(),
    /** Where "archive" and "delete" send each row, and whether they apply. */
    val targets: RowTargets = RowTargets(),
    val swipe: SwipeActions = SwipeActions(),
    val selection: Selection = Selection()
) {
    /** The selected conversations, in list order. */
    val selected: List<ConversationItem> get() = selection.pick(conversations)

    /** What the selection bar offers for [selected]. */
    val bulk: BulkAvailability get() = BulkAvailability.of(selected, targets)

    /** The empty state to show, or null while there is a list or more is still being loaded. */
    val empty: InboxEmpty?
        get() = when {
            !loaded || conversations.isNotEmpty() || hasMore -> null
            loadedCount > 0 -> InboxEmpty.FILTERED_OUT
            syncDisabled && !synced -> InboxEmpty.NOT_ENABLED
            !synced -> InboxEmpty.NOT_SYNCED
            else -> InboxEmpty.NO_MESSAGES
        }
}

/**
 * The conversation list of a folder or of the unified inbox. It keeps the query reactive and
 * grows its limit page by page, and it remembers the scope, the loaded pages and the scroll
 * position in saved state so they survive rotation and coming back to the screen.
 */
// One function per thing the list can do; they share the list, selection and swipe state.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val listing: InboxListing,
    accountListing: AccountListing,
    private val refreshTrigger: RefreshTrigger,
    private val savedState: SavedStateHandle,
    settings: SettingsRepository,
    private val runner: RowActionRunner
) : ViewModel() {
    private val scope = MutableStateFlow(InboxScope.fromKey(savedState.get<String>(SCOPE_KEY)))
    private val limit = MutableStateFlow(savedState.get<Int>(LIMIT_KEY) ?: PAGE_SIZE)
    private val filter = MutableStateFlow(
        savedState.get<String>(FILTER_KEY)?.let { name ->
            InboxFilter.entries.firstOrNull { it.name == name }
        } ?: InboxFilter.ALL
    )
    private val refreshing = MutableStateFlow(false)
    private val selection = MutableStateFlow(
        Selection(
            savedState.get<String>(SELECTION_SCOPE_KEY),
            savedState.get<ArrayList<String>>(SELECTION_KEYS_KEY).orEmpty().toSet()
        )
    )
    private val restores = MutableSharedFlow<String>(
        extraBufferCapacity = RESTORE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Rows (by key) that were swiped away but are still in the list: bring them back. */
    val restoreRequests: Flow<String> = restores

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

    private data class Status(
        val scope: InboxScope,
        val status: InboxStatus,
        val targets: RowTargets
    )

    private val statuses = scope.flatMapLatest { s ->
        if (s == null) {
            flowOf(null)
        } else {
            combine(listing.observeStatus(s), listing.observeTargets(s)) { status, targets ->
                Status(s, status, targets)
            }
        }
    }

    private val accounts = accountListing.observe()

    private val list = combine(
        pages,
        statuses,
        filter,
        refreshing,
        accounts
    ) { page, status, currentFilter, isRefreshing, allAccounts ->
        if (page == null || status == null || status.scope != page.scope) {
            InboxState(scope = scope.value, filter = currentFilter, refreshing = isRefreshing)
        } else {
            build(page, status, currentFilter, isRefreshing, allAccounts)
        }
    }

    val state: StateFlow<InboxState> = combine(
        list,
        selection,
        settings.swipeActions
    ) { current, picked, swipe ->
        current.copy(
            swipe = swipe,
            selection = picked.takeIf { it.scopeKey == current.scope?.key } ?: Selection()
        )
    }.onEach(::growWhileFilteredListIsShort).onEach(::dropVanished).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        InboxState()
    )

    private fun build(
        page: Page,
        found: Status,
        currentFilter: InboxFilter,
        isRefreshing: Boolean,
        allAccounts: List<AccountSummary>
    ): InboxState {
        val shown = when (currentFilter) {
            InboxFilter.ALL -> page.items
            InboxFilter.UNREAD -> page.items.filter { it.unread }
        }
        val folder = page.scope as? InboxScope.Folder
        val status = found.status
        return InboxState(
            scope = page.scope,
            loaded = true,
            folderName = status.folderName,
            folderRole = status.folderRole,
            accountEmail = folder?.let { f ->
                allAccounts.firstOrNull { it.id == f.accountId }?.email
            },
            synced = status.synced,
            syncDisabled = status.syncDisabled,
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
            },
            targets = found.targets
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
        setSelection(Selection(newScope.key))
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

    private fun setSelection(value: Selection) {
        selection.value = value
        savedState[SELECTION_SCOPE_KEY] = value.scopeKey
        savedState[SELECTION_KEYS_KEY] = ArrayList(value.keys)
    }

    /** Conversations that left the list (archived, filtered out...) are no longer selected. */
    private fun dropVanished(current: InboxState) {
        if (!current.loaded || current.scope?.key != selection.value.scopeKey) return
        val kept = selection.value.retain(current.conversations.mapTo(hashSetOf()) { it.key })
        if (kept !== selection.value) setSelection(kept)
    }

    /** Long-press, or a tap while selecting: picks the row, or takes it off. */
    fun toggleSelection(item: ConversationItem) {
        val key = scope.value?.key ?: return
        setSelection(selection.value.forScope(key).toggle(item.key))
    }

    fun selectAll() {
        val current = state.value
        if (!current.loaded) return
        setSelection(selection.value.selectAll(current.conversations.map { it.key }))
    }

    fun clearSelection() = setSelection(selection.value.clear())

    /**
     * A row was swiped to [direction]. Returns whether it leaves the list (the row slides out and
     * Room drops it); false springs it back. What the action does runs in the background.
     */
    fun onSwipe(item: ConversationItem, direction: SwipeDirection): Boolean {
        val current = state.value
        if (current.selection.active) return false
        val action = direction.action(current.swipe)
        return when (val decision = SwipePlanner.decide(action, item, current.targets)) {
            is SwipeDecision.Apply -> {
                run(decision.change, listOf(item), current.targets)
                decision.change.leavesList
            }

            SwipeDecision.PickFolder -> {
                pickFolder(listOf(item))
                false
            }

            is SwipeDecision.Blocked -> {
                runner.report(
                    if (decision.reason == SwipeBlock.NO_ARCHIVE_FOLDER) {
                        NoticeKind.NO_ARCHIVE_FOLDER
                    } else {
                        NoticeKind.NO_TRASH_FOLDER
                    }
                )
                false
            }

            SwipeDecision.Inactive -> false
        }
    }

    /** A button of the selection bar: [change] for every selected conversation. */
    fun applyToSelection(change: RowChange) {
        val current = state.value
        val items = current.selected
        if (items.isEmpty()) return
        clearSelection()
        run(change, items, current.targets)
    }

    /** The selection bar's "Move": opens the picker for the selected conversations. */
    fun moveSelection() {
        val items = state.value.selected
        if (items.isEmpty() || !state.value.bulk.canMove) return
        clearSelection()
        pickFolder(items)
    }

    private fun pickFolder(items: List<ConversationItem>) {
        viewModelScope.launch { runner.pickFolder(items) }
    }

    private fun run(change: RowChange, items: List<ConversationItem>, targets: RowTargets) {
        viewModelScope.launch {
            // Nothing changed (say, messages the server does not have): bring the rows back.
            if (!runner.run(change, items, targets)) items.forEach { restores.emit(it.key) }
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
        private const val SELECTION_SCOPE_KEY = "inbox.selectionScope"
        private const val SELECTION_KEYS_KEY = "inbox.selectionKeys"
        private const val RESTORE_BUFFER = 16
    }
}
