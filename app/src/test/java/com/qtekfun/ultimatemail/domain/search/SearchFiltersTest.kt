// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchFiltersTest {
    private val today = LocalDate.of(2026, 10, 3)

    private fun parse(text: String) = SearchQueryParser.parse(text)

    @Test
    fun `no chips change nothing`() {
        val query = parse("lunch is:read after:2026-01-01 ")

        assertTrue(SearchFilters.NONE.isEmpty)
        assertEquals(query, SearchFilters.NONE.applyTo(query, today))
    }

    @Test
    fun `chips turn on the matching conditions`() {
        val query = SearchFilters(unread = true, starred = true, withAttachments = true)
            .applyTo(SearchQuery.EMPTY, today)

        assertEquals(true, query.unread)
        assertTrue(query.starred)
        assertTrue(query.hasAttachment)
        assertFalse(query.isEmpty)
    }

    @Test
    fun `the Unread chip beats is read typed in the field`() {
        assertEquals(
            true,
            SearchFilters(unread = true).applyTo(parse("is:read "), today).unread
        )
    }

    @Test
    fun `last 7 days counts today as the seventh`() {
        val query = SearchFilters(date = DateFilter.Last7Days).applyTo(SearchQuery.EMPTY, today)

        assertEquals(LocalDate.of(2026, 9, 27), query.after)
        assertNull(query.before)
    }

    @Test
    fun `last 30 days and last year`() {
        assertEquals(
            LocalDate.of(2026, 9, 4),
            SearchFilters(date = DateFilter.Last30Days).applyTo(SearchQuery.EMPTY, today).after
        )
        assertEquals(
            LocalDate.of(2025, 10, 3),
            SearchFilters(date = DateFilter.LastYear).applyTo(SearchQuery.EMPTY, today).after
        )
    }

    @Test
    fun `a custom range includes its last day`() {
        val filter = SearchFilters(
            date = DateFilter.Custom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        )

        val query = filter.applyTo(SearchQuery.EMPTY, today)

        assertEquals(LocalDate.of(2026, 3, 1), query.after)
        assertEquals(LocalDate.of(2026, 4, 1), query.before)
    }

    @Test
    fun `a range open on one side limits only the other`() {
        val query = SearchFilters(date = DateFilter.Custom(null, LocalDate.of(2026, 3, 31)))
            .applyTo(SearchQuery.EMPTY, today)

        assertNull(query.after)
        assertEquals(LocalDate.of(2026, 4, 1), query.before)
    }

    @Test
    fun `typed dates and the chip combine to the narrower window`() {
        val typed = parse("after:2026-09-30 before:2026-12-01 ")

        val query = SearchFilters(date = DateFilter.Last7Days).applyTo(typed, today)

        assertEquals(LocalDate.of(2026, 9, 30), query.after)
        assertEquals(LocalDate.of(2026, 12, 1), query.before)
        val older = parse("after:2026-01-01 ")
        assertEquals(
            LocalDate.of(2026, 9, 27),
            SearchFilters(date = DateFilter.Last7Days).applyTo(older, today).after
        )
    }

    @Test
    fun `date filters survive saved state`() {
        val filters = listOf(
            DateFilter.AnyTime,
            DateFilter.Last7Days,
            DateFilter.Last30Days,
            DateFilter.LastYear,
            DateFilter.Custom(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 4)),
            DateFilter.Custom(null, LocalDate.of(2026, 3, 4)),
            DateFilter.Custom(LocalDate.of(2026, 1, 2), null)
        )

        filters.forEach { assertEquals(it, DateFilter.fromKey(it.key)) }
    }

    @Test
    fun `an unreadable date filter key means any time`() {
        assertEquals(DateFilter.AnyTime, DateFilter.fromKey(null))
        assertEquals(DateFilter.AnyTime, DateFilter.fromKey("custom//"))
        assertEquals(DateFilter.AnyTime, DateFilter.fromKey("custom/junk/junk"))
        assertEquals(DateFilter.AnyTime, DateFilter.fromKey("nonsense"))
    }

    @Test
    fun `scopes survive saved state, folder paths with slashes included`() {
        val scopes = listOf(
            SearchScope.AllAccounts,
            SearchScope.Account(3),
            SearchScope.Folder(3, "INBOX"),
            SearchScope.Folder(3, "Work/Invoices/2026")
        )

        scopes.forEach { assertEquals(it, SearchScope.fromKey(it.key)) }
    }

    @Test
    fun `an unreadable scope key is no scope`() {
        listOf(null, "", "folder/x/INBOX", "folder/1", "folder/1/", "account/1/x", "other/1")
            .forEach { assertNull(SearchScope.fromKey(it), it) }
    }

    @Test
    fun `a search starts in the folder it was opened from, or in every account`() {
        assertEquals(
            SearchScope.Folder(2, "Work"),
            SearchScope.startingFrom(
                InboxScope.Folder(2, "Work")
            )
        )
        assertEquals(
            SearchScope.AllAccounts,
            SearchScope.startingFrom(InboxScope.Unified)
        )
        assertEquals(SearchScope.AllAccounts, SearchScope.startingFrom(null))
        assertEquals(2L, SearchScope.Folder(2, "Work").accountId)
        assertNull(SearchScope.AllAccounts.accountId)
    }
}
