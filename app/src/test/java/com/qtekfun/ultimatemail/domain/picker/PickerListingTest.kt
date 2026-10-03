// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PickerListingTest {
    private val candidates = PickerCandidates.build(imapTree(), PickerMode.FOLDERS, setOf("INBOX"))
    private val search = FolderSearch(
        candidates.folders.map { SearchTarget(it.path, it.name, it.shownPath() ?: it.name) }
    )

    private fun listing(query: String, recents: List<String> = emptyList()) = PickerListing.build(
        candidates,
        query,
        recents,
        search
    ) { CheckState.UNCHECKED }

    private fun PickerListing.keys() = items.map { it.key }

    @Test
    fun `without a query the tree is shown in order`() {
        val result = listing("")

        assertEquals(
            listOf(
                "all:INBOX", "all:Sent", "all:Archive", "all:Trash", "all:Junk", "all:Personal",
                "group:Work", "all:Work/Clients", "all:Work/Invoices"
            ),
            result.keys()
        )
        assertFalse(result.noMatches)
    }

    @Test
    fun `a blank query is the same as no query`() {
        assertEquals(listing("").keys(), listing("   ").keys())
    }

    @Test
    fun `recent destinations come first under their own heading`() {
        val result = listing("", recents = listOf("Work/Invoices", "Sent"))

        assertEquals(
            listOf(
                "section:RECENT",
                "recent:Work/Invoices",
                "recent:Sent",
                "section:ALL",
                "all:INBOX"
            ),
            result.keys().take(5)
        )
    }

    @Test
    fun `recents that are gone, disabled or repeated are not shown`() {
        val result = listing("", recents = listOf("Deleted", "INBOX", "Sent", "Sent"))

        assertEquals(
            listOf("section:RECENT", "recent:Sent", "section:ALL"),
            result.keys().take(3)
        )
    }

    @Test
    fun `only the first few recents are shown`() {
        val many = candidates.folders.filter { it.enabled }.map { it.path }

        val shown = listing("", recents = many).items.count {
            it is PickerListItem.Entry &&
                it.recent
        }

        assertEquals(PickerListing.RECENT_LIMIT, shown)
    }

    @Test
    fun `without recents there is no recent heading`() {
        assertTrue(listing("").items.none { it is PickerListItem.Section })
    }

    @Test
    fun `recents are not shown while searching`() {
        val result = listing("sent", recents = listOf("Sent"))

        assertTrue(result.items.none { it is PickerListItem.Section })
        assertEquals(listOf("all:Sent"), result.keys())
    }

    @Test
    fun `a search lists the best matches first with their highlights`() {
        val result = listing("inv")

        val entry = result.items.single() as PickerListItem.Entry
        assertEquals("Work/Invoices", entry.folder.path)
        assertEquals(listOf(0..2), entry.nameHighlight)
        assertEquals(listOf(5..7), entry.pathHighlight)
        assertTrue(entry.showPath)
    }

    @Test
    fun `a search shows the path of the user's folders but not of special ones`() {
        val special = listing("junk").items.single() as PickerListItem.Entry
        val own = listing("personal").items.single() as PickerListItem.Entry

        assertFalse(special.showPath)
        assertFalse(own.showPath) // path and name are the same
    }

    @Test
    fun `a search with no result says so`() {
        val result = listing("zzzz")

        assertTrue(result.items.isEmpty())
        assertTrue(result.noMatches)
    }

    @Test
    fun `a search also finds the disabled current folder`() {
        val entry = listing("inbox").items.single() as PickerListItem.Entry

        assertFalse(entry.folder.enabled)
    }

    @Test
    fun `the check state comes from the selection`() {
        val result = PickerListing.build(candidates, "", emptyList(), search) {
            if (it.path == "Sent") CheckState.PARTIAL else CheckState.UNCHECKED
        }

        val states = result.items.filterIsInstance<PickerListItem.Entry>()
            .associate { it.folder.path to it.state }
        assertEquals(CheckState.PARTIAL, states["Sent"])
        assertEquals(CheckState.UNCHECKED, states["Trash"])
    }

    @Test
    fun `keys are unique when a folder is both recent and in the tree`() {
        val keys = listing("", recents = listOf("Sent")).keys()

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the empty listing has nothing and no message`() {
        assertTrue(PickerListing.Empty.items.isEmpty())
        assertFalse(PickerListing.Empty.noMatches)
    }

    @Test
    fun `a group row is described by its path`() {
        val group = listing("").items.filterIsInstance<PickerListItem.Group>().single()

        assertEquals("Work", group.name)
        assertEquals(0, group.depth)
    }
}
