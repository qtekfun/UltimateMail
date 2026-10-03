// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun summary(id: Long) =
    AccountSummary(id, "u$id@example.test", "User $id", AuthType.PASSWORD)

class ShellScopeTest {
    @Test
    fun `without accounts there is nothing to show`() {
        assertNull(ShellScope.default(emptyList(), null))
    }

    @Test
    fun `one account starts on its inbox`() {
        assertEquals(
            InboxScope.Folder(4, "Posteingang"),
            ShellScope.default(listOf(summary(4)), "Posteingang")
        )
        assertEquals(
            InboxScope.Folder(4, "INBOX"),
            ShellScope.default(listOf(summary(4)), null)
        )
    }

    @Test
    fun `several accounts start on the unified inbox`() {
        assertEquals(
            InboxScope.Unified,
            ShellScope.default(listOf(summary(1), summary(2)), "INBOX")
        )
    }

    @Test
    fun `nothing requested shows the default`() {
        assertEquals(
            InboxScope.Unified,
            ShellScope.resolve(null, listOf(summary(1)), InboxScope.Unified)
        )
    }

    @Test
    fun `a requested folder of an existing account is kept`() {
        val folder = InboxScope.Folder(2, "Work")

        assertEquals(
            folder,
            ShellScope.resolve(folder, listOf(summary(1), summary(2)), InboxScope.Unified)
        )
        assertEquals(
            InboxScope.Unified,
            ShellScope.resolve(
                InboxScope.Unified,
                listOf(summary(1)),
                InboxScope.Folder(1, "INBOX")
            )
        )
    }

    @Test
    fun `a folder of a removed account falls back to the default`() {
        val default = InboxScope.Folder(1, "INBOX")

        assertEquals(
            default,
            ShellScope.resolve(InboxScope.Folder(2, "Work"), listOf(summary(1)), default)
        )
    }
}
