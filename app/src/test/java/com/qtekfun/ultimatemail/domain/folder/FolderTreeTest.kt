// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun entity(
    path: String,
    name: String = path.substringAfterLast('/'),
    role: FolderRole = FolderRole.OTHER,
    isLabel: Boolean = false,
    syncEnabled: Boolean = true
) = FolderEntity(
    accountId = 1,
    path = path,
    name = name,
    role = role,
    isLabel = isLabel,
    syncEnabled = syncEnabled
)

private fun build(vararg folders: FolderEntity, unread: Map<String, Int> = emptyMap()) =
    FolderTree.build(folders.toList(), unread)

class FolderTreeTest {
    @Test
    fun `special folders come first in the fixed order`() {
        val tree = build(
            entity("Zeta"),
            entity("Junk", role = FolderRole.JUNK),
            entity("Trash", role = FolderRole.TRASH),
            entity("Starred", role = FolderRole.STARRED),
            entity("All", role = FolderRole.ALL_MAIL),
            entity("Archive", role = FolderRole.ARCHIVE),
            entity("Sent", role = FolderRole.SENT),
            entity("Drafts", role = FolderRole.DRAFTS),
            entity("INBOX", role = FolderRole.INBOX),
            entity("Alpha")
        )

        assertEquals(
            listOf("INBOX", "Drafts", "Sent", "Archive", "All", "Starred", "Trash", "Junk"),
            tree.special.map { it.path }
        )
        assertEquals(listOf("Alpha", "Zeta"), tree.nodes.map { it.path })
        assertEquals("INBOX", tree.inboxPath)
    }

    @Test
    fun `children follow their parent with their depth`() {
        val tree = build(
            entity("Work/Invoices/2026"),
            entity("Work"),
            entity("Work/Invoices"),
            entity("Work Stuff"),
            entity("Archive2"),
            entity("Work/Clients")
        )

        assertEquals(
            listOf(
                "Archive2" to 0,
                "Work" to 0,
                "Work/Clients" to 1,
                "Work/Invoices" to 1,
                "Work/Invoices/2026" to 2,
                "Work Stuff" to 0
            ),
            tree.nodes.map { it.path to it.depth }
        )
        assertEquals(
            listOf(false, true, false, true, false, false),
            tree.nodes.map { it.hasChildren }
        )
        assertEquals(
            listOf(null, null, "Work", "Work", "Work/Invoices", null),
            tree.nodes.map { it.parent }
        )
    }

    @Test
    fun `dotted hierarchies are nested as well`() {
        val tree = build(
            entity("INBOX.Lists.Kotlin", name = "Kotlin"),
            entity("INBOX.Lists", name = "Lists")
        )

        // The parent "INBOX" is not listed and not special here, so it becomes a container.
        assertEquals(
            listOf("INBOX" to 0, "INBOX.Lists" to 1, "INBOX.Lists.Kotlin" to 2),
            tree.nodes.map { it.path to it.depth }
        )
    }

    @Test
    fun `a missing parent is filled in with a container that cannot be opened`() {
        val tree = build(entity("Work/Invoices/2026"), entity("Other"))

        val container = tree.nodes.first { it.path == "Work" }
        assertEquals(
            listOf("Other", "Work", "Work/Invoices", "Work/Invoices/2026"),
            tree.nodes.map { it.path }
        )
        assertFalse(container.selectable)
        assertTrue(container.hasChildren)
        assertEquals(FolderRole.OTHER, container.role)
        assertFalse(tree.nodes.first { it.path == "Work/Invoices" }.selectable)
        assertTrue(tree.nodes.first { it.path == "Work/Invoices/2026" }.selectable)
        assertEquals(listOf(0, 1, 2), tree.nodes.drop(1).map { it.depth })
    }

    @Test
    fun `sorting ignores case`() {
        val tree = build(entity("beta"), entity("Alpha"))

        assertEquals(listOf("Alpha", "beta"), tree.nodes.map { it.path })
    }

    @Test
    fun `a child of a special folder is a top-level row and a repeated role goes to the tree`() {
        val tree = build(
            entity("INBOX", role = FolderRole.INBOX),
            entity("INBOX/Sub"),
            entity("Sent", role = FolderRole.SENT),
            entity("Sent Items", role = FolderRole.SENT)
        )

        assertEquals(listOf("INBOX", "Sent"), tree.special.map { it.path })
        assertEquals(listOf("Sent Items", "INBOX/Sub"), tree.nodes.map { it.path })
        assertEquals(listOf(0, 0), tree.nodes.map { it.depth })
        assertEquals(listOf(null, null), tree.nodes.map { it.parent })
    }

    @Test
    fun `gmail keeps its labels in a tree and drops the emptied container`() {
        val tree = build(
            entity("INBOX", role = FolderRole.INBOX),
            entity("[Gmail]", syncEnabled = false),
            entity("[Gmail]/Sent Mail", role = FolderRole.SENT),
            entity("[Gmail]/All Mail", role = FolderRole.ALL_MAIL, syncEnabled = false),
            entity("[Gmail]/Bin", role = FolderRole.TRASH),
            entity("Clients", isLabel = true),
            entity("Clients/Acme", isLabel = true)
        )

        assertEquals(
            listOf("INBOX", "[Gmail]/Sent Mail", "[Gmail]/All Mail", "[Gmail]/Bin"),
            tree.special.map { it.path }
        )
        assertEquals(listOf("Clients", "Clients/Acme"), tree.nodes.map { it.path })
        assertEquals(listOf(true, true), tree.nodes.map { it.isLabel })
    }

    @Test
    fun `a container that still has children is shown but cannot be selected`() {
        val tree = build(
            entity("[Gmail]", syncEnabled = false),
            entity("[Gmail]/Sent Mail", role = FolderRole.SENT),
            entity("[Gmail]/Important", isLabel = true)
        )

        assertEquals(listOf("[Gmail]", "[Gmail]/Important"), tree.nodes.map { it.path })
        assertFalse(tree.nodes.first().selectable)
        assertTrue(tree.nodes.last().selectable)
    }

    @Test
    fun `a folder the user stopped syncing stays selectable`() {
        val tree = build(entity("Old", syncEnabled = false))

        assertEquals(listOf("Old"), tree.nodes.map { it.path })
        assertTrue(tree.nodes.single().selectable)
    }

    @Test
    fun `a name that does not match the path counts as top level`() {
        val tree = build(entity("Odd/Path", name = "Shown"))

        assertEquals(0, tree.nodes.single().depth)
        assertNull(tree.nodes.single().parent)
    }

    @Test
    fun `unread counts and label flags are carried over`() {
        val tree = build(
            entity("INBOX", role = FolderRole.INBOX),
            entity("Work", isLabel = true),
            unread = mapOf("INBOX" to 3, "Work" to 2)
        )

        assertEquals(listOf(3), tree.special.map { it.unread })
        assertEquals(listOf(2), tree.nodes.map { it.unread })
        assertEquals(listOf(true), tree.nodes.map { it.isLabel })
    }

    @Test
    fun `no folders gives an empty tree without an inbox`() {
        val tree = build()

        assertTrue(tree.special.isEmpty())
        assertTrue(tree.nodes.isEmpty())
        assertNull(tree.inboxPath)
    }

    private val nested = build(
        entity("A"),
        entity("A/B"),
        entity("A/B/C"),
        entity("A/D"),
        entity("E")
    )

    @Test
    fun `collapsed parents hide all their descendants`() {
        assertEquals(listOf("A", "E"), nested.visible(emptySet()).map { it.path })
    }

    @Test
    fun `opening a parent shows its children, closed ones keep their own hidden`() {
        assertEquals(
            listOf("A", "A/B", "A/D", "E"),
            nested.visible(setOf("A")).map { it.path }
        )
        assertEquals(
            listOf("A", "A/B", "A/B/C", "A/D", "E"),
            nested.visible(setOf("A", "A/B")).map { it.path }
        )
    }

    @Test
    fun `an open child under a closed parent stays hidden`() {
        assertEquals(listOf("A", "E"), nested.visible(setOf("A/B")).map { it.path })
    }

    @Test
    fun `ancestors are listed nearest first`() {
        assertEquals(listOf("A/B", "A"), nested.ancestorsOf("A/B/C"))
        assertEquals(emptyList<String>(), nested.ancestorsOf("A"))
        assertEquals(emptyList<String>(), nested.ancestorsOf("unknown"))
    }

    @Test
    fun `the listing combines folders and unread counts from Room`() = runTest {
        val db = inMemoryDatabase()
        try {
            val id = db.accountDao().insert(account())
            db.folderDao().upsert(
                listOf(
                    entity("INBOX", role = FolderRole.INBOX).copy(accountId = id),
                    entity("Work").copy(accountId = id)
                )
            )
            val listing = FolderListing(db)

            listing.observe(id).test {
                val first = awaitItem()
                assertEquals(listOf(0), first.special.map { it.unread })
                db.messageDao().upsert(
                    listOf(message(id, uid = 1), message(id, uid = 2, seen = true))
                )
                assertEquals(listOf(1), awaitItem().special.map { it.unread })
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(InboxScope.Folder(id, "INBOX"), listing.inboxOf(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the inbox of an account that has not synced yet is INBOX`() = runTest {
        val db = inMemoryDatabase()
        try {
            val id = db.accountDao().insert(account())

            assertEquals(InboxScope.Folder(id, "INBOX"), FolderListing(db).inboxOf(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the inbox path follows the server`() = runTest {
        val db = inMemoryDatabase()
        try {
            val id = db.accountDao().insert(account())
            db.folderDao().upsert(
                listOf(entity("Posteingang", role = FolderRole.INBOX).copy(accountId = id))
            )

            assertEquals(InboxScope.Folder(id, "Posteingang"), FolderListing(db).inboxOf(id))
        } finally {
            db.close()
        }
    }
}
