// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import androidx.lifecycle.SavedStateHandle
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorTest {
    @Test
    fun `starts on the folder screen`() {
        assertEquals(Screen.Folders, AppNavigator(SavedStateHandle()).screen.value)
    }

    @Test
    fun `opening a screen shows it and back returns to the start`() {
        val navigator = AppNavigator(SavedStateHandle())

        navigator.open(Screen.AddAccount)
        assertEquals(Screen.AddAccount, navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.Folders, navigator.screen.value)
    }

    @Test
    fun `back on the start screen is left to the system`() {
        assertFalse(AppNavigator(SavedStateHandle()).back())
    }

    @Test
    fun `the screen is restored from saved state`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).open(Screen.AddAccount)

        assertEquals(Screen.AddAccount, AppNavigator(saved).screen.value)
    }

    @Test
    fun `an unknown saved route falls back to the start screen`() {
        assertEquals(Screen.Folders, Screen.fromRoute("gone"))
        assertEquals(Screen.Folders, Screen.fromRoute(null))
    }

    @Test
    fun `opening a folder or the unified inbox goes back to the folder list`() {
        val navigator = AppNavigator(SavedStateHandle())

        navigator.open(Screen.Inbox(InboxScope.Folder(3, "Work/Invoices")))
        assertEquals(Screen.Inbox(InboxScope.Folder(3, "Work/Invoices")), navigator.screen.value)
        assertTrue(navigator.back())
        assertEquals(Screen.Folders, navigator.screen.value)

        navigator.open(Screen.Inbox(InboxScope.Unified))
        assertTrue(navigator.back())
        assertEquals(Screen.Folders, navigator.screen.value)
    }

    @Test
    fun `an open conversation list is restored from saved state`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).open(Screen.Inbox(InboxScope.Folder(3, "Work/Invoices")))
        assertEquals(
            Screen.Inbox(InboxScope.Folder(3, "Work/Invoices")),
            AppNavigator(saved).screen.value
        )

        AppNavigator(saved).open(Screen.Inbox(InboxScope.Unified))
        assertEquals(Screen.Inbox(InboxScope.Unified), AppNavigator(saved).screen.value)
    }

    @Test
    fun `a broken inbox route falls back to the start screen`() {
        assertEquals(Screen.Folders, Screen.fromRoute("inbox:folder/x/INBOX"))
        assertEquals(Screen.Folders, Screen.fromRoute("inbox:"))
    }
}
