// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.settings.AccountProfile
import com.qtekfun.ultimatemail.domain.settings.AccountSettingsStore
import com.qtekfun.ultimatemail.domain.settings.FolderSync
import com.qtekfun.ultimatemail.domain.settings.OfflineWindow
import com.qtekfun.ultimatemail.domain.settings.ProfileError
import com.qtekfun.ultimatemail.domain.settings.ProfileRules
import com.qtekfun.ultimatemail.domain.settings.SaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the name and signature form stands: the user always knows whether it is stored. */
enum class ProfileStatus { SAVED, UNSAVED, INVALID, SAVING }

/**
 * What the settings of one account show. [loaded] is false until Room answered; once loaded,
 * [found] false means the account is gone (removed), and the screen leaves. [profile] is what the
 * form shows (the edits, or what is stored), [errors] what prevents saving it.
 */
data class AccountSettingsState(
    val loaded: Boolean = false,
    val found: Boolean = false,
    val email: String = "",
    val profile: AccountProfile = AccountProfile("", "", true, true),
    val status: ProfileStatus = ProfileStatus.SAVED,
    val errors: Set<ProfileError> = emptySet(),
    val offlineWindow: OfflineWindow = OfflineWindow.DAYS_90,
    val downloadForOffline: Boolean = true,
    val folders: List<FolderSync> = emptyList(),
    val confirmingRemoval: Boolean = false
)

/** The settings screen of one account: profile and signature, offline window, folders. */
@Suppress("TooManyFunctions") // One handler per control of the screen.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val store: AccountSettingsStore,
    private val removal: AccountRemoval,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val accountId = MutableStateFlow(savedState.get<Long>(ACCOUNT_KEY))

    /** The unsaved form, or null while the form shows what is stored. */
    private val edits = MutableStateFlow<AccountProfile?>(null)
    private val saving = MutableStateFlow(false)
    private val confirmingRemoval = MutableStateFlow(false)

    private val stored = accountId.flatMapLatest { id ->
        if (id == null) flowOf(null) else store.observe(id)
    }
    private val folders = accountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else store.observeFolders(id)
    }

    private class Form(val edits: AccountProfile?, val saving: Boolean, val confirming: Boolean)

    private val form = combine(edits, saving, confirmingRemoval, ::Form)

    val state: StateFlow<AccountSettingsState> = combine(stored, folders, form) {
            account,
            list,
            f
        ->
        if (account == null) {
            AccountSettingsState(loaded = accountId.value != null)
        } else {
            val shown = f.edits ?: account.profile
            val errors = ProfileRules.errors(shown)
            AccountSettingsState(
                loaded = true,
                found = true,
                email = account.email,
                profile = shown,
                status = when {
                    f.saving -> ProfileStatus.SAVING
                    errors.isNotEmpty() -> ProfileStatus.INVALID
                    f.edits != null && f.edits != account.profile -> ProfileStatus.UNSAVED
                    else -> ProfileStatus.SAVED
                },
                errors = errors,
                offlineWindow = account.offlineWindow,
                downloadForOffline = account.downloadForOffline,
                folders = list,
                confirmingRemoval = f.confirming
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        AccountSettingsState()
    )

    /** Shows the settings of [id]; edits that were not saved are dropped when it changes. */
    fun show(id: Long) {
        if (accountId.value == id) return
        edits.value = null
        confirmingRemoval.value = false
        accountId.value = id
        savedState[ACCOUNT_KEY] = id
    }

    fun onNameChange(name: String) = edit { it.copy(displayName = name) }

    fun onSignatureChange(text: String) = edit { it.copy(signature = text) }

    fun onSignatureEnabledChange(enabled: Boolean) = edit { it.copy(signatureEnabled = enabled) }

    fun onBeforeQuoteChange(beforeQuote: Boolean) =
        edit { it.copy(signatureBeforeQuote = beforeQuote) }

    private fun edit(change: (AccountProfile) -> AccountProfile) {
        val current = state.value
        if (!current.found || current.status == ProfileStatus.SAVING) return
        edits.value = change(current.profile)
    }

    /** Stores the name and signature; nothing happens while the form is invalid or unchanged. */
    fun save() {
        val id = accountId.value ?: return
        val current = state.value
        if (current.status != ProfileStatus.UNSAVED) return
        val saved = current.profile
        saving.value = true
        viewModelScope.launch {
            try {
                if (store.saveProfile(id, saved) == SaveResult.Saved) {
                    // Keep anything typed while saving; otherwise show what was stored.
                    edits.update { if (it == saved) null else it }
                }
            } finally {
                saving.value = false
            }
        }
    }

    fun onOfflineWindowChange(window: OfflineWindow) {
        val id = accountId.value ?: return
        viewModelScope.launch { store.setOfflineWindow(id, window) }
    }

    fun onDownloadForOfflineChange(enabled: Boolean) {
        val id = accountId.value ?: return
        viewModelScope.launch { store.setDownloadForOffline(id, enabled) }
    }

    fun onFolderSyncChange(path: String, enabled: Boolean) {
        val id = accountId.value ?: return
        viewModelScope.launch { store.setFolderSync(id, path, enabled) }
    }

    fun requestRemoval() {
        confirmingRemoval.value = true
    }

    fun dismissRemoval() {
        confirmingRemoval.value = false
    }

    /** Deletes the account with its credentials and local data, after the user agreed. */
    fun confirmRemoval() {
        val id = accountId.value ?: return
        confirmingRemoval.value = false
        viewModelScope.launch { removal.remove(id) }
    }

    private companion object {
        const val ACCOUNT_KEY = "settingsAccount"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
