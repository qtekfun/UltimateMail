// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentSearchLogTest {
    private fun log(vararg searches: String) =
        searches.fold(RecentSearchLog.EMPTY) { acc, text -> acc.recorded(text) }

    @Test
    fun `starts empty`() {
        assertTrue(RecentSearchLog.EMPTY.recent().isEmpty())
    }

    @Test
    fun `newest first`() {
        assertEquals(listOf("c", "b", "a"), log("a", "b", "c").recent())
    }

    @Test
    fun `repeating a search moves it to the front without duplicating it, case aside`() {
        assertEquals(listOf("A", "b"), log("a", "b", "A").recent())
    }

    @Test
    fun `blank text is not remembered`() {
        assertEquals(listOf("a"), log("a", "", "   ", "\n\t").recent())
    }

    @Test
    fun `blanks and line breaks inside a search collapse to one space`() {
        assertEquals(listOf("big news"), log("  big \n\t news  ").recent())
    }

    @Test
    fun `keeps at most ten`() {
        val many = (1..15).map { "search $it" }.toTypedArray()

        val recent = log(*many).recent()

        assertEquals(RecentSearchLog.MAX, recent.size)
        assertEquals("search 15", recent.first())
        assertEquals("search 6", recent.last())
    }

    @Test
    fun `removes one search, case aside`() {
        assertEquals(listOf("c", "a"), log("a", "b", "c").removed("B").recent())
        assertEquals(listOf("b", "a"), log("a", "b").removed("zzz").recent())
    }

    @Test
    fun `survives being stored and read back`() {
        val original = log("a b", "ç", "😀 x")

        assertEquals(original.recent(), RecentSearchLog.decode(original.encode()).recent())
    }

    @Test
    fun `damaged or oversized stored text is cleaned up`() {
        assertTrue(RecentSearchLog.decode(null).recent().isEmpty())
        assertTrue(RecentSearchLog.decode("").recent().isEmpty())
        assertEquals(listOf("a", "b"), RecentSearchLog.decode("a\n\n  \nA\nb").recent())
        val long = (1..30).joinToString("\n") { "s$it" }
        assertEquals(RecentSearchLog.MAX, RecentSearchLog.decode(long).recent().size)
    }
}
