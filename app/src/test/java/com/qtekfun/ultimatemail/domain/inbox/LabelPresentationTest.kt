// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabelPresentationTest {
    @Test
    fun `no labels give no chips`() {
        assertTrue(LabelPresentation.summarize(emptyList()).isEmpty)
        assertEquals(LabelSummary.EMPTY, LabelPresentation.summarize(listOf("", "  ")))
    }

    @Test
    fun `system labels and keywords are not shown`() {
        val summary = LabelPresentation.summarize(
            listOf("\\Inbox", "\\Important", "\$Forwarded", "\$label1", "Work")
        )

        assertEquals(listOf("Work"), summary.chips.map { it.text })
        assertEquals(0, summary.overflow)
    }

    @Test
    fun `the folder being shown is not repeated as a chip`() {
        val summary = LabelPresentation.summarize(listOf("Work", "Clients"), hidden = setOf("Work"))

        assertEquals(listOf("Clients"), summary.chips.map { it.label })
    }

    @Test
    fun `a nested label shows its last part and keeps the full name`() {
        val chip = LabelPresentation.summarize(listOf("Work/Invoices")).chips.single()

        assertEquals("Invoices", chip.text)
        assertEquals("Work/Invoices", chip.label)
    }

    @Test
    fun `a label ending in a slash still shows something`() {
        assertEquals("Odd/", LabelPresentation.summarize(listOf("Odd/")).chips.single().text)
    }

    @Test
    fun `duplicates and surrounding spaces collapse and the order is kept`() {
        val summary = LabelPresentation.summarize(listOf(" Work ", "Clients", "Work"))

        assertEquals(listOf("Work", "Clients"), summary.chips.map { it.label })
    }

    @Test
    fun `chips beyond the maximum become an overflow count`() {
        val summary = LabelPresentation.summarize(listOf("A", "B", "C", "D", "E"))

        assertEquals(listOf("A", "B", "C"), summary.chips.map { it.text })
        assertEquals(2, summary.overflow)
        assertEquals(1, LabelPresentation.summarize(listOf("A", "B", "C"), maxChips = 2).overflow)
    }

    @Test
    fun `the colour depends only on the label and stays in the palette`() {
        val work = LabelPresentation.summarize(listOf("Work")).chips.single().colorIndex
        val workAgain = LabelPresentation.summarize(listOf("Other", "Work")).chips.last().colorIndex

        assertEquals(1, work)
        assertEquals(work, workAgain)
        assertEquals(
            4,
            LabelPresentation.summarize(listOf("Work/Invoices")).chips.single().colorIndex
        )
        val all = (0 until 200).map {
            LabelPresentation.summarize(listOf("label-$it")).chips.single().colorIndex
        }
        assertTrue(all.all { it in 0 until LabelPresentation.PALETTE_SIZE })
    }
}
