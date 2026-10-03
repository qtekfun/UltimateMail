// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current [Screen] and whether the side menu is open; both survive rotation and
 * process death through saved state. The menu belongs here so that Back can close it first.
 */
@HiltViewModel
class AppNavigator @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
    private val current = MutableStateFlow(Screen.fromRoute(savedState.get<String>(KEY)))
    private var cameFrom: Screen? = savedState.get<String>(CAME_FROM_KEY)?.let(Screen::fromRoute)
    private val drawer = MutableStateFlow(savedState.get<Boolean>(DRAWER_KEY) ?: false)

    val screen: StateFlow<Screen> = current.asStateFlow()

    /** Whether the side menu is open (or opening). The UI follows this value. */
    val drawerOpen: StateFlow<Boolean> = drawer.asStateFlow()

    /** Shows [screen]; the side menu is closed. */
    fun open(screen: Screen) {
        current.value = screen
        savedState[KEY] = screen.route
        setDrawerOpen(false)
    }

    /**
     * Opens the conversation [threadId] of [folderPath]. Back returns to the screen this was
     * called from (a folder, the unified inbox), not to the start screen.
     */
    fun openConversation(accountId: Long, folderPath: String, threadId: String) {
        // From another conversation the way back stays what it was.
        if (current.value !is Screen.Conversation) {
            cameFrom = current.value
            savedState[CAME_FROM_KEY] = current.value.route
        }
        open(Screen.Conversation(accountId, folderPath, threadId))
    }

    /**
     * Shows the conversations of [scope] and closes the side menu. [home] is the scope the start
     * screen already shows: asking for it goes back to the start screen, so Back from any other
     * folder returns there and the next Back leaves the app.
     */
    fun showScope(scope: InboxScope, home: InboxScope?) {
        open(if (scope == home) Screen.Home else Screen.Inbox(scope))
    }

    fun setDrawerOpen(open: Boolean) {
        drawer.value = open
        savedState[DRAWER_KEY] = open
    }

    /**
     * Handles the Back button: closes the side menu if it is open, otherwise goes back to the
     * start screen. Returns false when there is nothing left to go back from (the app closes).
     */
    fun back(): Boolean = when {
        drawer.value -> {
            setDrawerOpen(false)
            true
        }

        current.value is Screen.Conversation -> {
            open(cameFrom ?: Screen.Home)
            true
        }

        current.value == Screen.Home -> false

        else -> {
            open(Screen.Home)
            true
        }
    }

    private companion object {
        const val KEY = "screen"
        const val DRAWER_KEY = "drawerOpen"
        const val CAME_FROM_KEY = "cameFrom"
    }
}
