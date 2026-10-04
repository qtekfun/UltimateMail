// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.nav

import androidx.lifecycle.SavedStateHandle
import com.qtekfun.ultimatemail.domain.conversation.ConversationRef
import com.qtekfun.ultimatemail.domain.conversation.ReaderList
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationNavigationTest {
    private val folder = InboxScope.Folder(3, "Work/Invoices")

    @Test
    fun `a conversation route restores the same screen whatever characters it holds`() {
        val screens = listOf(
            Screen.Conversation(1, "INBOX", "12345"),
            Screen.Conversation(7, "[Gmail]/All Mail", "thr:ead/with%odd chars+é"),
            Screen.Conversation(2, "Work: A/B", "<id@host>")
        )

        screens.forEach { assertEquals(it, Screen.fromRoute(it.route), it.route) }
    }

    @Test
    fun `a damaged conversation route goes to the start screen`() {
        listOf(
            "conversation:",
            "conversation:x:INBOX:t",
            "conversation:1:INBOX",
            "conversation:1::t",
            "conversation:1:INBOX:t:extra"
        ).forEach { assertEquals(Screen.Home, Screen.fromRoute(it), it) }
    }

    @Test
    fun `the reference names the thread`() {
        assertEquals(
            ConversationRef(4, "INBOX", "t1"),
            Screen.Conversation(4, "INBOX", "t1").ref
        )
    }

    @Test
    fun `back from a conversation returns to the folder it was opened from`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.showScope(folder, home = null)

        navigator.openConversation(3, "Work/Invoices", "t1")
        assertEquals(Screen.Conversation(3, "Work/Invoices", "t1"), navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.Inbox(folder), navigator.screen.value)
    }

    @Test
    fun `back from a conversation opened on the start screen returns there`() {
        val navigator = AppNavigator(SavedStateHandle())

        navigator.openConversation(1, "INBOX", "t")
        assertTrue(navigator.back())

        assertEquals(Screen.Home, navigator.screen.value)
        assertFalse(navigator.back())
    }

    @Test
    fun `the screen it came from survives the process being recreated`() {
        val saved = SavedStateHandle()
        val first = AppNavigator(saved)
        first.showScope(folder, home = null)
        first.openConversation(3, "Work/Invoices", "t1")

        val restored = AppNavigator(saved)

        assertEquals(Screen.Conversation(3, "Work/Invoices", "t1"), restored.screen.value)
        assertTrue(restored.back())
        assertEquals(Screen.Inbox(folder), restored.screen.value)
    }

    @Test
    fun `opening a conversation from a conversation keeps the original way back`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.showScope(folder, home = null)
        navigator.openConversation(3, "Work/Invoices", "t1")

        navigator.openConversation(3, "Work/Invoices", "t2")
        navigator.back()

        assertEquals(Screen.Inbox(folder), navigator.screen.value)
    }

    @Test
    fun `the list a conversation was opened from is kept while moving between neighbours`() {
        val navigator = AppNavigator(SavedStateHandle())
        val list = ReaderList(folder, unreadOnly = true)
        navigator.openConversation(3, "Work/Invoices", "t1", from = list)

        navigator.openConversation(3, "Work/Invoices", "t2")

        assertEquals(list, navigator.readerList.value)
        navigator.back()
        navigator.openConversation(1, "INBOX", "t")
        assertEquals(null, navigator.readerList.value)
    }

    @Test
    fun `the list survives the process being recreated`() {
        val saved = SavedStateHandle()
        val list = ReaderList(InboxScope.Unified)
        AppNavigator(saved).openConversation(1, "INBOX", "t", from = list)

        assertEquals(list, AppNavigator(saved).readerList.value)
    }

    @Test
    fun `back closes the menu before leaving a conversation`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.openConversation(1, "INBOX", "t")
        navigator.setDrawerOpen(true)

        assertTrue(navigator.back())

        assertTrue(navigator.screen.value is Screen.Conversation)
        assertFalse(navigator.drawerOpen.value)
    }

    @Test
    fun `a reauth route restores the same screen`() {
        val screen = Screen.Reauth(42)

        assertEquals(screen, Screen.fromRoute(screen.route))
    }

    @Test
    fun `a damaged reauth route goes to the start screen`() {
        listOf("reauth:", "reauth:x", "reauth:1:2").forEach {
            assertEquals(Screen.Home, Screen.fromRoute(it), it)
        }
    }

    @Test
    fun `back from the sign in screen returns to where it was opened`() {
        val navigator = AppNavigator(SavedStateHandle())
        navigator.open(Screen.AccountSettings(5))

        navigator.openReauth(5)
        assertEquals(Screen.Reauth(5), navigator.screen.value)

        assertTrue(navigator.back())
        assertEquals(Screen.AccountSettings(5), navigator.screen.value)
    }

    @Test
    fun `the sign in screen survives a recreated navigator`() {
        val saved = SavedStateHandle()
        AppNavigator(saved).openReauth(9)

        assertEquals(Screen.Reauth(9), AppNavigator(saved).screen.value)
    }
}
