// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentLogTest {
    @Test
    fun `an empty log has nothing`() {
        assertTrue(RecentLog.Empty.recent().isEmpty())
        assertTrue(RecentLog.Empty.usage().isEmpty())
        assertEquals("", RecentLog.Empty.encode())
    }

    @Test
    fun `the most recently used folder comes first`() {
        val log = RecentLog.Empty.recorded(listOf("A")).recorded(listOf("B")).recorded(listOf("C"))

        assertEquals(listOf("C", "B", "A"), log.recent())
    }

    @Test
    fun `using a folder again brings it to the front and counts it`() {
        val log = RecentLog.Empty.recorded(listOf("A", "B")).recorded(listOf("A"))

        assertEquals(listOf("A", "B"), log.recent())
        assertEquals(mapOf("A" to 2, "B" to 1), log.usage())
    }

    @Test
    fun `of several folders used together the last one is the most recent`() {
        val log = RecentLog.Empty.recorded(listOf("A", "B", "C"))

        assertEquals(listOf("C", "B", "A"), log.recent())
    }

    @Test
    fun `only the most recent entries are kept`() {
        var log = RecentLog.Empty
        (1..RecentLog.MAX_ENTRIES + 20).forEach { log = log.recorded(listOf("F$it")) }

        assertEquals(RecentLog.MAX_ENTRIES, log.recent().size)
        assertEquals("F${RecentLog.MAX_ENTRIES + 20}", log.recent().first())
        assertTrue("F1" !in log.recent())
    }

    @Test
    fun `a log survives being written and read`() {
        val log = RecentLog.Empty.recorded(listOf("A", "B")).recorded(listOf("A"))

        val read = RecentLog.decode(log.encode())

        assertEquals(log.recent(), read.recent())
        assertEquals(log.usage(), read.usage())
    }

    @Test
    fun `paths with tabs, newlines, backslashes and unicode survive`() {
        val paths = listOf("a\tb", "c\nd", "e\\f", "g\\th", "Niños/東京 💡", "trailing\\")
        val log = RecentLog.Empty.recorded(paths)

        val read = RecentLog.decode(log.encode())

        assertEquals(log.recent(), read.recent())
        assertEquals(paths.toSet(), read.usage().keys)
    }

    @Test
    fun `reading nothing gives an empty log`() {
        assertTrue(RecentLog.decode(null).recent().isEmpty())
        assertTrue(RecentLog.decode("").recent().isEmpty())
    }

    @Test
    fun `damaged lines are skipped and the rest is read`() {
        val read = RecentLog.decode(
            "garbage\n2\t5\tGood\nx\t1\tBadCount\n1\ty\tBadTime\n1\t3\t\n3\t4\tAlso"
        )

        assertEquals(listOf("Good", "Also"), read.recent())
        assertEquals(mapOf("Good" to 2, "Also" to 3), read.usage())
    }

    @Test
    fun `a path listed twice keeps its first line`() {
        val read = RecentLog.decode("1\t1\tA\n5\t2\tA")

        assertEquals(mapOf("A" to 1), read.usage())
    }

    @Test
    fun `recording after reading continues the clock`() {
        val read = RecentLog.decode("1\t10\tOld")

        assertEquals(listOf("New", "Old"), read.recorded(listOf("New")).recent())
    }
}
