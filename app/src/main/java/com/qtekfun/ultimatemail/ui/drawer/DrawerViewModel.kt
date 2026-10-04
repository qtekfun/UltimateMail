// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.folder.FolderExpansion
import com.qtekfun.ultimatemail.domain.folder.FolderListing
import com.qtekfun.ultimatemail.domain.folder.FolderTree
import com.qtekfun.ultimatemail.domain.folder.MailboxMenu
import com.qtekfun.ultimatemail.domain.folder.ShellScope
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the side menu shows. [loaded] is false until Room has answered the first time.
 * [defaultScope] is what the main screen shows until the user picks something (see
 * [ShellScope]); [mailboxes] has the rows (see [MailboxMenu]) and [selected] is the account
 * whose special mailboxes they list.
 */
data class FolderMenuState(
    val loaded: Boolean = false,
    val accounts: List<AccountSummary> = emptyList(),
    val selected: AccountSummary? = null,
    val defaultScope: InboxScope? = null,
    val mailboxes: MailboxMenu = MailboxMenu(),
    val syncLine: SyncLine = SyncLine.NeverSynced
)

/**
 * The content of the side menu: the inbox of each account, the special mailboxes of the selected
 * account, one collapsible section of folders per account (all from Room only) with their
 * expand/collapse state, and the sync status line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DrawerViewModel @Inject constructor(
    accountListing: AccountListing,
    private val folderListing: FolderListing,
    syncStatus: SyncStatus,
    private val scheduler: SyncScheduler,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val selectedId = MutableStateFlow(savedState.get<Long>(SELECTED_KEY))
    private val expansion =
        MutableStateFlow(FolderExpansion.fromSaved(savedState.get<ArrayList<String>>(EXPANDED_KEY)))

    private val accountsAndSelection = combine(accountListing.observe(), selectedId) { list, id ->
        list to (list.firstOrNull { it.id == id } ?: list.firstOrNull())
    }

    private class Content(
        val accounts: List<AccountSummary>,
        val selected: AccountSummary?,
        val trees: Map<Long, FolderTree>,
        val syncLine: SyncLine
    )

    /** Accounts, selection, folders and sync status come out of one flow, so they agree. */
    private val content = accountsAndSelection.flatMapLatest { (accounts, selected) ->
        val trees = if (accounts.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(accounts.map { account -> folderListing.observe(account.id) }) { all ->
                accounts.map { it.id }.zip(all).toMap()
            }
        }
        val sync = selected?.let { syncStatus.observe(it.id) } ?: flowOf(null)
        combine(trees, sync) { t, s -> Content(accounts, selected, t, SyncLine.of(s)) }
    }

    val state: StateFlow<FolderMenuState> = combine(content, expansion) { content, expanded ->
        FolderMenuState(
            loaded = true,
            accounts = content.accounts,
            selected = content.selected,
            defaultScope = ShellScope.default(
                content.accounts,
                content.selected?.let { content.trees[it.id]?.inboxPath }
            ),
            mailboxes = MailboxMenu.build(
                content.accounts,
                content.trees,
                content.selected,
                expanded
            ),
            syncLine = content.syncLine
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        FolderMenuState()
    )

    /** Makes [accountId] the account whose folders the menu lists. */
    fun select(accountId: Long) {
        selectedId.value = accountId
        savedState[SELECTED_KEY] = accountId
    }

    /** Switches the menu to [accountId] and returns the Inbox to show for it. */
    suspend fun switchAccount(accountId: Long): InboxScope {
        select(accountId)
        return folderListing.inboxOf(accountId)
    }

    /**
     * The main screen now shows [scope]: the menu follows its account, and the parents above
     * the folder are opened so the highlighted row is visible.
     */
    fun onScopeShown(scope: InboxScope) {
        if (scope !is InboxScope.Folder) return
        select(scope.accountId)
        viewModelScope.launch {
            val tree = folderListing.observe(scope.accountId).first()
            if (tree.inTree(scope.path)) {
                update(
                    expansion.value.revealSection(scope.accountId)
                        .reveal(scope.accountId, tree.ancestorsOf(scope.path))
                )
            }
        }
    }

    /** Opens or closes a parent folder inside the section of [accountId]. */
    fun toggleFolder(accountId: Long, path: String) {
        update(expansion.value.toggle(accountId, path))
    }

    /** Opens or closes the section with the folders and labels of [accountId]. */
    fun toggleSection(accountId: Long) {
        update(expansion.value.toggleSection(accountId))
    }

    /** Syncs every account now; also retries accounts waiting for the user to sign in again. */
    fun refresh() {
        scheduler.requestSync(userInitiated = true)
    }

    private fun update(next: FolderExpansion) {
        expansion.value = next
        savedState[EXPANDED_KEY] = next.toSaved()
    }

    private companion object {
        const val SELECTED_KEY = "selectedAccount"
        const val EXPANDED_KEY = "expandedFolders"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
