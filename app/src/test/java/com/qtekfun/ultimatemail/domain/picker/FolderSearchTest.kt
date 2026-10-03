// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun target(path: String, usage: Int = 0) =
    SearchTarget(path, path.substringAfterLast('/'), path, usage)

private fun search(query: String, vararg paths: String): List<String> =
    FolderSearch(paths.map { target(it) }).search(query).map { it.id }

class FolderSearchTest {
    @Test
    fun `a blank query finds nothing`() {
        assertEquals(emptyList<String>(), search("", "Work"))
        assertEquals(emptyList<String>(), search("   ", "Work"))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("Work/Invoices"), search("INVOICES", "Work/Invoices", "Personal"))
        assertEquals(listOf("WORK"), search("work", "WORK", "Personal"))
    }

    @Test
    fun `matching ignores accents in the query and in the folder`() {
        assertEquals(listOf("Facturación"), search("facturacion", "Facturación", "Other"))
        assertEquals(listOf("Facturacion"), search("facturación", "Facturacion", "Other"))
        assertEquals(listOf("Niños"), search("ninos", "Niños"))
        assertEquals(listOf("Ñandú"), search("nandu", "Ñandú"))
        assertEquals(listOf("Projects/Über"), search("uber", "Projects/Über"))
        assertEquals(listOf("Projects/Über"), search("ÜBER", "Projects/Über"))
    }

    @Test
    fun `a word of the middle of the path finds the folder`() {
        assertEquals(
            listOf("Work/Invoices 2025/Facturas"),
            search("fact", "Work/Invoices 2025/Facturas", "Work/Clients")
        )
        assertEquals(
            listOf("Work/Invoices 2025/Facturas"),
            search("work", "Work/Invoices 2025/Facturas", "Personal")
        )
    }

    @Test
    fun `several words must all match, in any order`() {
        val paths = arrayOf("Work/Invoices 2025/Facturas", "Work/Clients", "Invoices")

        assertEquals(listOf("Work/Invoices 2025/Facturas"), search("fact inv", *paths))
        assertEquals(listOf("Work/Invoices 2025/Facturas"), search("inv work", *paths))
        assertEquals(emptyList<String>(), search("fact clients", *paths))
    }

    @Test
    fun `a query with a slash matches across segments`() {
        assertEquals(
            listOf("Work/Invoices"),
            search("work/inv", "Work/Invoices", "Work/Clients", "Invoices")
        )
    }

    @Test
    fun `nothing matches a word that is nowhere`() {
        assertEquals(emptyList<String>(), search("zzz", "Work", "Personal"))
    }

    @Test
    fun `non latin names and emoji can be found`() {
        val paths = arrayOf("Проекты/Планы", "旅行/東京", "Ideas 💡", "Plain")

        assertEquals(listOf("Проекты/Планы"), search("планы", *paths))
        assertEquals(listOf("Проекты/Планы"), search("ПРОЕКТ", *paths))
        assertEquals(listOf("旅行/東京"), search("東京", *paths))
        assertEquals(listOf("Ideas 💡"), search("💡", *paths))
    }

    @Test
    fun `ranking puts exact before prefix before word start before substring`() {
        val hits = FolderSearch(
            listOf(
                target("Mytax"),
                target("Income tax"),
                target("Tax Returns"),
                target("Tax")
            )
        ).search("tax")

        assertEquals(
            listOf("Tax", "Tax Returns", "Income tax", "Mytax"),
            hits.map { it.id }
        )
        assertEquals(
            listOf(
                MatchLevel.EXACT,
                MatchLevel.PREFIX,
                MatchLevel.WORD_START,
                MatchLevel.SUBSTRING
            ),
            hits.map { it.level }
        )
    }

    @Test
    fun `the whole path equal to the query is an exact match`() {
        val hits = FolderSearch(listOf(target("Work/Invoices"), target("Invoices")))
            .search("work/invoices")

        assertEquals(MatchLevel.EXACT, hits.single().level)
    }

    @Test
    fun `the whole query at the start of a name or path outranks word starts`() {
        val hits = FolderSearch(
            listOf(target("Clients/Acme/Notes"), target("Notes/Acme"), target("Acme/Notes"))
        ).search("acme")

        assertEquals(listOf("Notes/Acme", "Acme/Notes", "Clients/Acme/Notes"), hits.map { it.id })
        assertEquals(
            listOf(MatchLevel.EXACT, MatchLevel.PREFIX, MatchLevel.WORD_START),
            hits.map { it.level }
        )
    }

    @Test
    fun `at the same level a match in the name beats one only in the path`() {
        val hits = FolderSearch(listOf(target("My Invoices/Misc"), target("Work/Big Invoices")))
            .search("inv")

        assertEquals(listOf(MatchLevel.WORD_START, MatchLevel.WORD_START), hits.map { it.level })
        assertEquals(listOf("Work/Big Invoices", "My Invoices/Misc"), hits.map { it.id })
    }

    @Test
    fun `equal matches are ordered by usage and then by name`() {
        val search = FolderSearch(
            listOf(
                SearchTarget("b", "Abe", "Abe", usage = 1),
                SearchTarget("a", "Abc", "Abc", usage = 1),
                SearchTarget("c", "Abd", "Abd", usage = 9)
            )
        )

        assertEquals(listOf("c", "a", "b"), search.search("ab").map { it.id })
    }

    @Test
    fun `usage never beats a better match`() {
        val search = FolderSearch(
            listOf(
                SearchTarget("popular", "Mytax", "Mytax", usage = 500),
                SearchTarget("exact", "Tax", "Tax", usage = 0)
            )
        )

        assertEquals(listOf("exact", "popular"), search.search("tax").map { it.id })
    }

    @Test
    fun `the highlight covers the matched part of the name and the path`() {
        val hit = FolderSearch(
            listOf(target("Work/Invoices 2025/Facturas"))
        ).search("fact").single()

        assertEquals(listOf(0..3), hit.nameRanges)
        assertEquals(listOf(19..22), hit.pathRanges)
    }

    @Test
    fun `the highlight is in the original text even with accents`() {
        val hit = FolderSearch(listOf(target("Facturación"))).search("cion").single()

        assertEquals(listOf(7..10), hit.nameRanges)
    }

    @Test
    fun `the highlight prefers the start of a word to an earlier substring`() {
        // "ar" is inside "Carta" but starts "Arte".
        val hit = FolderSearch(
            listOf(SearchTarget("x", "Carta Arte", "Carta Arte"))
        ).search("ar").single()

        assertEquals(listOf(6..7), hit.nameRanges)
        assertEquals(MatchLevel.WORD_START, hit.level)
    }

    @Test
    fun `overlapping and touching highlights are merged`() {
        val hit = FolderSearch(listOf(target("Invoices"))).search("inv voi").single()

        assertEquals(listOf(0..4), hit.nameRanges)
    }

    @Test
    fun `a match only in the path has no name highlight`() {
        val hit = FolderSearch(listOf(target("Work/Invoices"))).search("work").single()

        assertEquals(emptyList<IntRange>(), hit.nameRanges)
        assertEquals(listOf(0..3), hit.pathRanges)
    }

    @Test
    fun `a very long path is searched like any other`() {
        val long = (1..400).joinToString("/") { "segment$it" } + "/Facturas"
        val hit = FolderSearch(
            listOf(SearchTarget(long, "Facturas", long))
        ).search("segment399 fact")

        assertEquals(1, hit.size)
        assertEquals(MatchLevel.WORD_START, hit.single().level)
    }

    @Test
    fun `thousands of folders filter quickly`() {
        val targets = (1..20_000).map {
            SearchTarget(
                "p$it",
                "Folder número $it",
                "Archive/${it % 50}/Folder número $it",
                it % 7
            )
        }
        val search = FolderSearch(targets)

        var hits = emptyList<SearchHit>()
        val millis = measureTimeMillis { repeat(10) { hits = search.search("numero 19999") } }

        assertEquals("p19999", hits.first().id)
        // A generous bound: this guards against an accidental quadratic, not a stopwatch.
        assertTrue(millis < 10_000, "10 searches over 20000 folders took $millis ms")
    }
}
