// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailboxMenuTest {
    private val ana = AccountSummary(1, "ana@example.test", "Ana", AuthType.PASSWORD)
    private val bea = AccountSummary(2, "bea@example.test", "Bea", AuthType.PASSWORD)

    private fun entity(accountId: Long, path: String, role: FolderRole = FolderRole.OTHER) =
        FolderEntity(
            accountId = accountId,
            path = path,
            name = path.substringAfterLast('/'),
            role = role
        )

    private val anaTree = FolderTree.build(
        listOf(
            entity(1, "INBOX", FolderRole.INBOX),
            entity(1, "Trash", FolderRole.TRASH),
            entity(1, "Junk", FolderRole.JUNK),
            entity(1, "Archive", FolderRole.ARCHIVE),
            entity(1, "Sent", FolderRole.SENT),
            entity(1, "Drafts", FolderRole.DRAFTS),
            entity(1, "Starred", FolderRole.STARRED),
            entity(1, "Work"),
            entity(1, "Work/Invoices")
        ),
        mapOf("INBOX" to 3, "Drafts" to 1)
    )
    private val beaTree = FolderTree.build(
        listOf(entity(2, "Posteingang", FolderRole.INBOX), entity(2, "Misc")),
        mapOf("Posteingang" to 4)
    )
    private val trees = mapOf(1L to anaTree, 2L to beaTree)

    private fun build(
        selected: AccountSummary? = ana,
        expansion: FolderExpansion = FolderExpansion()
    ) = MailboxMenu.build(listOf(ana, bea), trees, selected, expansion)

    @Test
    fun `every account gets an inbox row with its own unread count and scope`() {
        val menu = build()

        assertEquals(
            listOf(InboxScope.Folder(1, "INBOX"), InboxScope.Folder(2, "Posteingang")),
            menu.inboxes.map { it.scope }
        )
        assertEquals(listOf(3, 4), menu.inboxes.map { it.unread })
        assertEquals(7, menu.unifiedUnread)
    }

    @Test
    fun `an account that has not synced its folders points at INBOX with no count`() {
        val menu = MailboxMenu.build(listOf(ana), emptyMap(), ana, FolderExpansion())

        assertEquals(InboxScope.Folder(1, "INBOX"), menu.inboxes.single().scope)
        assertEquals(0, menu.unifiedUnread)
        assertTrue(menu.special.isEmpty())
        assertTrue(menu.sections.single().folders.isEmpty())
    }

    @Test
    fun `special mailboxes are those of the selected account in the fixed order`() {
        assertEquals(
            listOf("Starred", "Drafts", "Sent", "Archive", "Junk", "Trash"),
            build(ana).special.map { it.path }
        )
        assertEquals(1, build(ana).special.first { it.path == "Drafts" }.unread)
        assertTrue(build(bea).special.isEmpty())
    }

    @Test
    fun `all mail is listed with the special mailboxes and the inbox is not repeated`() {
        val gmail = FolderTree.build(
            listOf(
                entity(1, "INBOX", FolderRole.INBOX),
                entity(1, "[Gmail]/All Mail", FolderRole.ALL_MAIL),
                entity(1, "[Gmail]/Spam", FolderRole.JUNK)
            ),
            emptyMap()
        )
        val menu = MailboxMenu.build(listOf(ana), mapOf(1L to gmail), ana, FolderExpansion())

        assertEquals(listOf("[Gmail]/All Mail", "[Gmail]/Spam"), menu.special.map { it.path })
    }

    @Test
    fun `sections start closed and hold no rows`() {
        val menu = build()

        assertEquals(listOf(1L, 2L), menu.sections.map { it.account.id })
        assertTrue(menu.sections.none { it.open })
        assertTrue(menu.sections.all { it.folders.isEmpty() })
    }

    @Test
    fun `an open section lists its folders without the special ones and keeps parents closed`() {
        val menu = build(expansion = FolderExpansion().toggleSection(1))

        val section = menu.sections.first { it.account.id == 1L }
        assertTrue(section.open)
        assertEquals(listOf("Work"), section.folders.map { it.path })
        assertFalse(menu.sections.first { it.account.id == 2L }.open)
    }

    @Test
    fun `opened parents show their children only inside their own section`() {
        val expansion = FolderExpansion().toggleSection(1).toggle(1, "Work")
        val menu = build(expansion = expansion)

        val section = menu.sections.first { it.account.id == 1L }
        assertEquals(listOf("Work", "Work/Invoices"), section.folders.map { it.path })
        assertEquals(setOf("Work"), section.expanded)
        assertTrue(menu.sections.first { it.account.id == 2L }.expanded.isEmpty())
    }

    @Test
    fun `without a selected account there are no special mailboxes`() {
        assertTrue(build(selected = null).special.isEmpty())
    }
}
