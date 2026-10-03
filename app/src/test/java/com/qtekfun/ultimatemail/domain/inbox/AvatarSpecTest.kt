// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvatarSpecTest {
    @Test
    fun `the colour is pinned to the address so it never changes between launches`() {
        // String.hashCode is specified by Java, so these slots are the same on every device.
        assertEquals(6, AvatarSpec.of("Ana", "ana@example.test").colorIndex)
        assertEquals(3, AvatarSpec.of("Bob", "bob@example.test").colorIndex)
    }

    @Test
    fun `the colour ignores the name, the case and the spaces of the address`() {
        val expected = AvatarSpec.of("Ana", "ana@example.test").colorIndex

        assertEquals(
            expected,
            AvatarSpec.of("Completely other name", "ana@example.test").colorIndex
        )
        assertEquals(expected, AvatarSpec.of("Ana", "  ANA@Example.TEST ").colorIndex)
        assertNotEquals(expected, AvatarSpec.of("Ana", "bob@example.test").colorIndex)
    }

    @Test
    fun `without an address the colour comes from the name`() {
        assertEquals(
            AvatarSpec.of("Ana", "").colorIndex,
            AvatarSpec.of(" ana ", "").colorIndex
        )
    }

    @Test
    fun `a negative hash still gives a slot inside the palette`() {
        // "Clients".hashCode() is negative.
        assertEquals(2, AvatarSpec.colorIndexOf("Clients", AvatarSpec.PALETTE_SIZE))
        val slots = (0 until 500).map { AvatarSpec.of("x", "user$it@example.test").colorIndex }
        assertTrue(slots.all { it in 0 until AvatarSpec.PALETTE_SIZE })
        assertEquals(AvatarSpec.PALETTE_SIZE, slots.toSet().size)
    }

    @Test
    fun `the initial is the first letter of the name in capitals`() {
        assertEquals("A", AvatarSpec.of("Ana Pérez", "ana@example.test").initial)
        assertEquals("B", AvatarSpec.of("bob", "bob@example.test").initial)
        assertEquals("Á", AvatarSpec.of("álvaro", "a@example.test").initial)
    }

    @Test
    fun `quotes, spaces and punctuation before the name are skipped`() {
        assertEquals("H", AvatarSpec.of("  \"¡Hola\"", "h@example.test").initial)
        assertEquals("M", AvatarSpec.of("(Marketing) team", "m@example.test").initial)
    }

    @Test
    fun `digits count as an initial`() {
        assertEquals("4", AvatarSpec.of("42 Corp", "c@example.test").initial)
    }

    @Test
    fun `an empty or blank name falls back to the address`() {
        assertEquals("Z", AvatarSpec.of("", "zoe@example.test").initial)
        assertEquals("Z", AvatarSpec.of("   ", "zoe@example.test").initial)
        assertEquals("Z", AvatarSpec.of("---", "zoe@example.test").initial)
    }

    @Test
    fun `without a name or an address the initial is a question mark`() {
        assertEquals("?", AvatarSpec.of("", "").initial)
        assertEquals("?", AvatarSpec.of(" ", "<>").initial)
    }

    @Test
    fun `an emoji is kept whole as the initial`() {
        assertEquals("😀", AvatarSpec.of("😀 Party", "p@example.test").initial)
        // A family is several code points joined by zero-width joiners, one visible character.
        val family = "👨‍👩‍👧"
        assertEquals(family, AvatarSpec.of("$family Family", "f@example.test").initial)
    }

    @Test
    fun `non-Latin names give their first character`() {
        assertEquals("李", AvatarSpec.of("李雷", "li@example.test").initial)
        assertEquals("И", AvatarSpec.of("иван Петров", "i@example.test").initial)
        assertEquals("Α", AvatarSpec.of("αλέξανδρος", "a@example.test").initial)
        assertEquals("م", AvatarSpec.of("محمد", "m@example.test").initial)
    }

    @Test
    fun `an accent written as a combining mark stays with its letter`() {
        assertEquals("É", AvatarSpec.of("émile", "e@example.test").initial)
    }
}
