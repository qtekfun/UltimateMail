// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the current [Screen]; it survives rotation and process death through saved state. */
@HiltViewModel
class AppNavigator @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
    private val current = MutableStateFlow(Screen.fromRoute(savedState.get<String>(KEY)))
    val screen: StateFlow<Screen> = current.asStateFlow()

    fun open(screen: Screen) {
        current.value = screen
        savedState[KEY] = screen.route
    }

    /** Goes back to the start screen. Returns false when already there (the app should close). */
    fun back(): Boolean {
        if (current.value == Screen.Folders) return false
        open(Screen.Folders)
        return true
    }

    private companion object {
        const val KEY = "screen"
    }
}
