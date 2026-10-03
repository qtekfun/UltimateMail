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
    private val work = InboxScope.Folder(3, "Work/Invoices")

    @Test
    fun `starts on the home screen with the menu closed`() {
        val navigator = AppNavigator(SavedStateHandle())

        assertEquals(Screen.Home, navigator.screen.value)
        assertFalse(navigator.drawerOpen.value)
    }

    @Test
    fun `opening a screen shows it and back returns to the start`() {
        val navigator = AppNavigator(SavedStateHandle())

        navigator.open(Screen.AddAccount)
        assertEquals(Screen.AddAccount, navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.Home, navigator.screen.value)
    }

    @Test
    fun `back on the start screen with the menu closed is left to the system`() {
        assertFalse(AppNavigator(SavedStateHandle()).back())
    }

    @Test
    fun `back closes an open menu first and only then goes back`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.showScope(work, home = null)
        navigator.setDrawerOpen(true)

        assertTrue(navigator.back())
        assertFalse(navigator.drawerOpen.value)
        assertEquals(Screen.Inbox(work), navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.Home, navigator.screen.value)

        assertFalse(navigator.back())
    }

    @Test
    fun `back closes the menu on the start screen without leaving the app`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.setDrawerOpen(true)

        assertTrue(navigator.back())
        assertFalse(navigator.drawerOpen.value)
        assertFalse(navigator.back())
    }

    @Test
    fun `choosing a folder closes the menu and shows it`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.setDrawerOpen(true)

        navigator.showScope(work, home = InboxScope.Unified)

        assertEquals(Screen.Inbox(work), navigator.screen.value)
        assertFalse(navigator.drawerOpen.value)
    }

    @Test
    fun `choosing the folder the start screen already shows goes to the start screen`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.showScope(work, home = InboxScope.Unified)

        navigator.showScope(InboxScope.Unified, home = InboxScope.Unified)

        assertEquals(Screen.Home, navigator.screen.value)
    }

    @Test
    fun `opening another screen closes the menu`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.setDrawerOpen(true)

        navigator.open(Screen.AddAccount)

        assertFalse(navigator.drawerOpen.value)
    }

    @Test
    fun `the screen is restored from saved state`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).open(Screen.AddAccount)

        assertEquals(Screen.AddAccount, AppNavigator(saved).screen.value)
    }

    @Test
    fun `an open menu is restored from saved state`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).setDrawerOpen(true)
        assertTrue(AppNavigator(saved).drawerOpen.value)

        AppNavigator(saved).setDrawerOpen(false)
        assertFalse(AppNavigator(saved).drawerOpen.value)
    }

    @Test
    fun `an open conversation list is restored from saved state`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).open(Screen.Inbox(work))
        assertEquals(Screen.Inbox(work), AppNavigator(saved).screen.value)

        AppNavigator(saved).open(Screen.Inbox(InboxScope.Unified))
        assertEquals(Screen.Inbox(InboxScope.Unified), AppNavigator(saved).screen.value)
    }

    @Test
    fun `routes round-trip for every screen`() {
        listOf(
            Screen.Home,
            Screen.AddAccount,
            Screen.Settings,
            Screen.AccountSettings(7),
            Screen.ExportAccounts,
            Screen.ImportAccounts,
            Screen.Inbox(work),
            Screen.Inbox(InboxScope.Unified)
        ).forEach { assertEquals(it, Screen.fromRoute(it.route)) }
    }

    @Test
    fun `back from export or import returns to settings`() {
        val navigator = AppNavigator(SavedStateHandle())

        listOf(Screen.ExportAccounts, Screen.ImportAccounts).forEach { screen ->
            navigator.open(screen)
            assertTrue(navigator.back())
            assertEquals(Screen.Settings, navigator.screen.value)
        }
    }

    @Test
    fun `an unknown or broken route falls back to the start screen`() {
        assertEquals(Screen.Home, Screen.fromRoute("gone"))
        assertEquals(Screen.Home, Screen.fromRoute(null))
        assertEquals(Screen.Home, Screen.fromRoute("folders"))
        assertEquals(Screen.Home, Screen.fromRoute("inbox:folder/x/INBOX"))
        assertEquals(Screen.Home, Screen.fromRoute("inbox:"))
        assertEquals(Screen.Home, Screen.fromRoute("account-settings:abc"))
    }

    @Test
    fun `back from account settings returns to settings, then to the start screen`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.open(Screen.Settings)
        navigator.open(Screen.AccountSettings(7))

        assertTrue(navigator.back())
        assertEquals(Screen.Settings, navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.Home, navigator.screen.value)
    }

    @Test
    fun `the composer returns to the screen it was opened from`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.openConversation(1, "INBOX", "t1")

        navigator.openCompose(42)
        assertEquals(Screen.Compose(42), navigator.screen.value)

        navigator.closeCompose()
        assertEquals(Screen.Conversation(1, "INBOX", "t1"), navigator.screen.value)
        // The way back out of the reader is still the list it came from.
        navigator.back()
        assertEquals(Screen.Home, navigator.screen.value)
    }

    @Test
    fun `reopening the composer from the composer keeps the original way back`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.open(Screen.Inbox(work))
        navigator.openCompose(1)

        navigator.openCompose(2)
        navigator.back()

        assertEquals(Screen.Inbox(work), navigator.screen.value)
    }

    @Test
    fun `the composer and the outbox survive process death by route`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).openCompose(7)
        assertEquals(Screen.Compose(7), AppNavigator(saved).screen.value)

        assertEquals(Screen.Outbox, Screen.fromRoute(Screen.Outbox.route))
        assertEquals(Screen.Home, Screen.fromRoute("compose:abc"))
    }
}
