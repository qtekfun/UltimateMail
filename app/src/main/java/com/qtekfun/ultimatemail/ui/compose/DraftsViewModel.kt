// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.compose.ComposeEngine
import com.qtekfun.ultimatemail.domain.compose.ComposeState
import com.qtekfun.ultimatemail.domain.compose.DraftListItem
import com.qtekfun.ultimatemail.domain.compose.ServerDraft
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Drafts list: [items] newest first, and the draft waiting for a delete confirmation. */
data class DraftsUiState(
    val loaded: Boolean = false,
    val items: List<DraftListItem> = emptyList(),
    val confirmingDelete: Long? = null
)

/**
 * The Drafts view of the side menu (RF-07): the drafts being written on this device plus the
 * copies in the server's Drafts folder that have no local draft. A local draft opens in the
 * composer and can be deleted (after confirming). A draft that only exists on the server is
 * opened in the composer too: the entry imports the copy as a local draft that replaces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DraftsViewModel @Inject constructor(
    composeState: ComposeState,
    private val engine: ComposeEngine,
    private val entry: ComposeEntry,
    private val swiped: SwipeDiscards
) : ViewModel() {
    private val account = MutableStateFlow<Long?>(null)
    private val confirming = MutableStateFlow<Long?>(null)

    val state: StateFlow<DraftsUiState> = combine(
        account.flatMapLatest { composeState.observeDrafts(it) },
        confirming,
        swiped.ids
    ) { items, asking, hidden ->
        DraftsUiState(
            loaded = true,
            items = items.filterNot { it is DraftListItem.Local && it.draft.id in hidden },
            confirmingDelete = asking
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            DraftsUiState()
        )

    /** Lists the drafts of [accountId]. */
    fun show(accountId: Long) {
        account.value = accountId
    }

    /** Opens a draft written here in the composer. */
    fun open(draftId: Long) = entry.request(ComposeStart.Draft(draftId))

    /** Opens a draft that is only on the server in the composer (it becomes a local draft). */
    fun openServerDraft(draft: ServerDraft) =
        entry.request(ComposeStart.ServerDraft(draft.messageRowId))

    fun requestDelete(draftId: Long) {
        confirming.value = draftId
    }

    fun dismissDelete() {
        confirming.value = null
    }

    fun confirmDelete() {
        val id = confirming.value ?: return
        confirming.value = null
        viewModelScope.launch { engine.discard(id) }
    }

    /**
     * A swipe on a draft: it leaves the list now and is thrown away (with its copy on the server,
     * through the operation queue) when the Undo window ends. Undo only shows it again.
     */
    fun swipeDelete(draftId: Long) {
        swiped.hide(draftId)
        swiped.offerUndo(draftId, NoticeKind.DRAFT_DISCARDED) {
            engine.discard(draftId, onlyEditing = true)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
