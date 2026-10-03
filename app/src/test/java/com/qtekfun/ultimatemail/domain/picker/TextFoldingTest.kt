// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TextFoldingTest {
    @Test
    fun `case is ignored`() {
        assertEquals("work/invoices", TextFolding.foldedString("WoRk/INVOICES"))
    }

    @Test
    fun `accents and diacritics are removed`() {
        assertEquals("facturacion nino uber", TextFolding.foldedString("Facturación Niño Über"))
        assertEquals("elodie", TextFolding.foldedString("Élodie"))
    }

    @Test
    fun `decomposed and precomposed forms fold the same`() {
        assertEquals(
            TextFolding.foldedString("ñ"),
            TextFolding.foldedString("ñ")
        )
        assertEquals("n", TextFolding.foldedString("Ñ"))
    }

    @Test
    fun `a dotted capital I becomes a plain i`() {
        assertEquals("istanbul", TextFolding.foldedString("İstanbul"))
    }

    @Test
    fun `full width letters fold to ascii`() {
        assertEquals("abc", TextFolding.foldedString("ＡＢＣ"))
    }

    @Test
    fun `non latin scripts are lowercased and otherwise kept`() {
        assertEquals("проекты", TextFolding.foldedString("ПРОЕКТЫ"))
        assertEquals("東京", TextFolding.foldedString("東京"))
        assertEquals("ελληνικα", TextFolding.foldedString("ΕΛΛΗΝΙΚΆ"))
    }

    @Test
    fun `emoji survive`() {
        assertEquals("ideas 💡", TextFolding.foldedString("Ideas 💡"))
    }

    @Test
    fun `an empty string folds to an empty string`() {
        assertEquals("", TextFolding.foldedString(""))
    }

    @Test
    fun `ranges map back to the original text across accents`() {
        val folded = TextFolding.fold("Año nuevo")

        // "nuevo" is at 4 until 9 in both texts here; "no" is at 1 until 3 ("ño").
        assertEquals(1..2, folded.originalRange(1, 3))
        assertEquals(4..8, folded.originalRange(4, 9))
    }

    @Test
    fun `a character that expands maps every part to the one original character`() {
        val folded = TextFolding.fold("ﬁx")

        assertEquals("fix", folded.text)
        assertEquals(0..0, folded.originalRange(0, 2))
        assertEquals(0..1, folded.originalRange(0, 3))
    }

    @Test
    fun `an emoji is one original range of two characters`() {
        val folded = TextFolding.fold("a💡b")

        assertEquals(1..2, folded.originalRange(1, 3))
    }

    @Test
    fun `an empty or outside range is refused`() {
        val folded = TextFolding.fold("abc")

        assertThrows(IllegalArgumentException::class.java) { folded.originalRange(1, 1) }
        assertThrows(IllegalArgumentException::class.java) { folded.originalRange(0, 4) }
    }
}
