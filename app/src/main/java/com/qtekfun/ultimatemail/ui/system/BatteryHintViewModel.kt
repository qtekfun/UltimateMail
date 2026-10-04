// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import com.qtekfun.ultimatemail.domain.account.AccountListing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Whether the one-time "let the sync run with the screen off" question is still to be asked. */
@HiltViewModel
class BatteryHintViewModel @Inject constructor(
    accounts: AccountListing,
    private val preferences: PreferenceStore
) : ViewModel() {
    private val done = MutableStateFlow(preferences.getBoolean(KEY_DONE, false))

    /** True once there is an account and the question was not answered or dismissed yet. */
    val pending: StateFlow<Boolean> = combine(accounts.observe(), done) { list, answered ->
        list.isNotEmpty() && !answered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    fun markDone() {
        preferences.putBoolean(KEY_DONE, true)
        done.value = true
    }

    companion object {
        const val KEY_DONE = "battery_hint_done"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
