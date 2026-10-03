// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import androidx.lifecycle.SavedStateHandle
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.domain.search.SearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SearchNavigationTest {
    @Test
    fun `search routes survive being saved, folder paths with slashes included`() {
        listOf(
            SearchScope.AllAccounts,
            SearchScope.Account(4),
            SearchScope.Folder(4, "Work/Invoices")
        ).forEach {
            assertEquals(Screen.Search(it), Screen.fromRoute(Screen.Search(it).route))
        }
    }

    @Test
    fun `an unreadable search route searches every account`() {
        assertEquals(Screen.Search(SearchScope.AllAccounts), Screen.fromRoute("search:junk"))
    }

    @Test
    fun `Back from the search returns to the folder it was opened from`() {
        val navigator = AppNavigator(SavedStateHandle())
        val folder = Screen.Inbox(InboxScope.Folder(1, "Work"))
        navigator.open(folder)

        navigator.openSearch(SearchScope.Folder(1, "Work"))
        assertEquals(Screen.Search(SearchScope.Folder(1, "Work")), navigator.screen.value)

        assertEquals(true, navigator.back())
        assertEquals(folder, navigator.screen.value)
    }

    @Test
    fun `Back from a conversation opened from the search returns to the search first`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.openSearch(SearchScope.AllAccounts)

        navigator.openConversation(1, "INBOX", "t")
        navigator.back()
        assertEquals(Screen.Search(SearchScope.AllAccounts), navigator.screen.value)

        navigator.back()
        assertEquals(Screen.Home, navigator.screen.value)
        assertFalse(navigator.back())
    }

    @Test
    fun `where the search was opened from survives recreation`() {
        val saved = SavedStateHandle()
        val first = AppNavigator(saved)
        val folder = Screen.Inbox(InboxScope.Folder(1, "Work"))
        first.open(folder)
        first.openSearch(SearchScope.AllAccounts)

        val second = AppNavigator(saved)
        second.back()

        assertEquals(folder, second.screen.value)
    }
}
