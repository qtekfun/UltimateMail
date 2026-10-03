// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the settings screen shows: the device preferences and the accounts to open. */
data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val accounts: List<AccountSummary> = emptyList()
)

/**
 * The device-wide settings. The theme of the whole app follows [state] too, so it starts from
 * the stored value (read synchronously) and the first frame already has the right colors.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    accountListing: AccountListing
) : ViewModel() {
    val state: StateFlow<SettingsState> = combine(
        repository.settings,
        accountListing.observe()
    ) { settings, accounts -> SettingsState(settings, accounts) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SettingsState(repository.current())
        )

    fun setTheme(theme: ThemeMode) = repository.setTheme(theme)

    fun setDynamicColor(enabled: Boolean) = repository.setDynamicColor(enabled)

    fun setAmoled(enabled: Boolean) = repository.setAmoled(enabled)

    fun setSwipeRight(action: SwipeAction) = repository.setSwipeRight(action)

    fun setSwipeLeft(action: SwipeAction) = repository.setSwipeLeft(action)

    fun setRemoteContent(policy: RemoteContentPolicy) = repository.setRemoteContent(policy)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
