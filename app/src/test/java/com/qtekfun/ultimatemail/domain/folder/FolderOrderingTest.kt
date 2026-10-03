// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.folder

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun entity(
    path: String,
    name: String = path.substringAfterLast('/'),
    role: FolderRole = FolderRole.OTHER,
    isLabel: Boolean = false
) = FolderEntity(accountId = 1, path = path, name = name, role = role, isLabel = isLabel)

class FolderOrderingTest {
    @Test
    fun `special folders come first in the fixed order`() {
        val folders = listOf(
            entity("Zeta"),
            entity("Junk", role = FolderRole.JUNK),
            entity("Trash", role = FolderRole.TRASH),
            entity("Archive", role = FolderRole.ARCHIVE),
            entity("Sent", role = FolderRole.SENT),
            entity("Drafts", role = FolderRole.DRAFTS),
            entity("INBOX", role = FolderRole.INBOX),
            entity("Alpha")
        )

        val paths = FolderOrdering.order(folders, emptyMap()).map { it.path }

        assertEquals(
            listOf("INBOX", "Drafts", "Sent", "Archive", "Trash", "Junk", "Alpha", "Zeta"),
            paths
        )
    }

    @Test
    fun `children follow their parent with their depth`() {
        val folders = listOf(
            entity("Work/Invoices/2026"),
            entity("Work"),
            entity("Work/Invoices"),
            entity("Work Stuff"),
            entity("Archive2", role = FolderRole.OTHER),
            entity("Work/Clients")
        )

        val items = FolderOrdering.order(folders, emptyMap())

        assertEquals(
            listOf(
                "Archive2" to 0,
                "Work" to 0,
                "Work/Clients" to 1,
                "Work/Invoices" to 1,
                "Work/Invoices/2026" to 2,
                "Work Stuff" to 0
            ),
            items.map { it.path to it.depth }
        )
    }

    @Test
    fun `dotted hierarchies are nested as well`() {
        val folders = listOf(
            entity("INBOX.Lists.Kotlin", name = "Kotlin"),
            entity("INBOX.Lists", name = "Lists")
        )

        val items = FolderOrdering.order(folders, emptyMap())

        assertEquals(
            listOf("INBOX.Lists" to 1, "INBOX.Lists.Kotlin" to 2),
            items.map {
                it.path to
                    it.depth
            }
        )
    }

    @Test
    fun `sorting ignores case`() {
        val items = FolderOrdering.order(listOf(entity("beta"), entity("Alpha")), emptyMap())

        assertEquals(listOf("Alpha", "beta"), items.map { it.path })
    }

    @Test
    fun `children of special folders stay visible and a repeated role goes to the tree`() {
        val folders = listOf(
            entity("INBOX", role = FolderRole.INBOX),
            entity("INBOX/Sub"),
            entity("Sent", role = FolderRole.SENT),
            entity("Sent Items", role = FolderRole.SENT)
        )

        val items = FolderOrdering.order(folders, emptyMap())

        assertEquals(listOf("INBOX", "Sent", "INBOX/Sub", "Sent Items"), items.map { it.path })
        assertEquals(listOf(0, 0, 1, 0), items.map { it.depth })
    }

    @Test
    fun `a name that does not match the path counts as top level`() {
        val items = FolderOrdering.order(listOf(entity("Odd/Path", name = "Shown")), emptyMap())

        assertEquals(0, items.single().depth)
    }

    @Test
    fun `unread counts and label flags are carried over`() {
        val folders =
            listOf(entity("INBOX", role = FolderRole.INBOX), entity("Work", isLabel = true))

        val items = FolderOrdering.order(folders, mapOf("INBOX" to 3))

        assertEquals(listOf(3, 0), items.map { it.unread })
        assertEquals(listOf(false, true), items.map { it.isLabel })
    }

    @Test
    fun `no folders gives an empty list`() {
        assertEquals(emptyList<FolderListItem>(), FolderOrdering.order(emptyList(), emptyMap()))
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
                assertEquals(listOf(0, 0), awaitItem().map { it.unread })
                db.messageDao().upsert(
                    listOf(message(id, uid = 1), message(id, uid = 2, seen = true))
                )
                assertEquals(listOf(1, 0), awaitItem().map { it.unread })
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            db.close()
        }
    }
}
