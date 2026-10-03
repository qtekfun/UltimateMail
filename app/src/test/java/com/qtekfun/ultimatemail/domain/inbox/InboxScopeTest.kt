// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InboxScopeTest {
    @Test
    fun `a folder scope survives a round trip, including slashes in the path`() {
        val scope = InboxScope.Folder(7, "Work/Invoices/2026")

        assertEquals(scope, InboxScope.fromKey(scope.key))
    }

    @Test
    fun `the unified scope survives a round trip`() {
        assertEquals(InboxScope.Unified, InboxScope.fromKey(InboxScope.Unified.key))
    }

    @Test
    fun `keys that are not scopes give null`() {
        assertNull(InboxScope.fromKey(null))
        assertNull(InboxScope.fromKey(""))
        assertNull(InboxScope.fromKey("folder"))
        assertNull(InboxScope.fromKey("folder/x/INBOX"))
        assertNull(InboxScope.fromKey("folder/1/"))
        assertNull(InboxScope.fromKey("other/1/INBOX"))
    }
}
