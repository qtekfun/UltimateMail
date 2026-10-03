// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.folder.FolderListItem
import com.qtekfun.ultimatemail.domain.folder.FolderListing
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the folder screen shows. [loaded] is false until Room has answered the first time. */
data class FolderListState(
    val loaded: Boolean = false,
    val accounts: List<AccountSummary> = emptyList(),
    val selected: AccountSummary? = null,
    val folders: List<FolderListItem> = emptyList(),
    val confirmingRemoval: Boolean = false
)

/** Accounts, the selected account's folders (from Room only) and removing an account. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderListViewModel @Inject constructor(
    accountListing: AccountListing,
    folderListing: FolderListing,
    private val removal: AccountRemoval,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val selectedId = MutableStateFlow(savedState.get<Long>(SELECTED_KEY))
    private val confirmingRemoval = MutableStateFlow(false)

    private val accountsAndSelection = combine(accountListing.observe(), selectedId) { list, id ->
        list to (list.firstOrNull { it.id == id } ?: list.firstOrNull())
    }

    /** Accounts, selection and folders come out of one flow, so they never disagree. */
    private val content = accountsAndSelection.flatMapLatest { (accounts, selected) ->
        val folders = selected?.let { folderListing.observe(it.id) } ?: flowOf(emptyList())
        folders.map { FolderListState(true, accounts, selected, it) }
    }

    val state: StateFlow<FolderListState> = combine(content, confirmingRemoval) {
            content,
            confirming
        ->
        content.copy(confirmingRemoval = confirming && content.selected != null)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        FolderListState()
    )

    fun select(accountId: Long) {
        selectedId.value = accountId
        savedState[SELECTED_KEY] = accountId
    }

    fun requestRemoval() {
        confirmingRemoval.value = true
    }

    fun dismissRemoval() {
        confirmingRemoval.value = false
    }

    /** Deletes the selected account with its credentials and local data, after the user agreed. */
    fun confirmRemoval() {
        val account = state.value.selected ?: return
        confirmingRemoval.value = false
        viewModelScope.launch { removal.remove(account.id) }
    }

    private companion object {
        const val SELECTED_KEY = "selectedAccount"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
