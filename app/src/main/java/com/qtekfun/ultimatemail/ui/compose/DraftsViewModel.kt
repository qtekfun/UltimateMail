// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.compose.ComposeEngine
import com.qtekfun.ultimatemail.domain.compose.ComposeState
import com.qtekfun.ultimatemail.domain.compose.DraftListItem
import com.qtekfun.ultimatemail.domain.conversation.ConversationRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * opened in the reader, where the normal actions (delete included) apply to it: the engine does
 * not import server drafts into the composer yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DraftsViewModel @Inject constructor(
    composeState: ComposeState,
    private val engine: ComposeEngine,
    private val messages: MessageDao,
    private val entry: ComposeEntry,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val account = MutableStateFlow<Long?>(null)
    private val confirming = MutableStateFlow<Long?>(null)

    val state: StateFlow<DraftsUiState> = combine(
        account.flatMapLatest { composeState.observeDrafts(it) },
        confirming
    ) { items, asking -> DraftsUiState(loaded = true, items = items, confirmingDelete = asking) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DraftsUiState())

    /** Lists the drafts of [accountId]. */
    fun show(accountId: Long) {
        account.value = accountId
    }

    /** Opens a draft written here in the composer. */
    fun open(draftId: Long) = entry.request(ComposeStart.Draft(draftId))

    /** The conversation holding a draft that is only on the server, or null if it is gone. */
    suspend fun serverDraft(item: DraftListItem.OnServer): ConversationRef? =
        withContext(io) {
            messages.getById(item.draft.messageRowId)?.let {
                ConversationRef(it.accountId, it.folderPath, it.threadId)
            }
        }

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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
