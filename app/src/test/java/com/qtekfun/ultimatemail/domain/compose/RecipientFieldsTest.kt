// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipientFieldsTest {
    private val empty = RecipientFieldState()

    @Test
    fun `plain typing only changes the text`() {
        val field = RecipientFields.typed(empty, "an")

        assertEquals("an", field.input)
        assertTrue(field.chips.isEmpty())
    }

    @Test
    fun `a comma turns the typed address into a chip and keeps what follows`() {
        val field = RecipientFields.typed(empty, "ana@example.test, bo")

        assertEquals(listOf("ana@example.test"), field.addresses.map { it.address })
        assertEquals("bo", field.input)
    }

    @Test
    fun `a space after a complete address commits it but a space in a name does not`() {
        val committed = RecipientFields.typed(empty, "ana@example.test ")
        val typingName = RecipientFields.typed(empty, "Ana ")

        assertEquals(1, committed.chips.size)
        assertEquals("", committed.input)
        assertTrue(typingName.chips.isEmpty())
        assertEquals("Ana ", typingName.input)
    }

    @Test
    fun `pasting a list makes one chip per entry and keeps names with commas together`() {
        val field = RecipientFields.typed(
            empty,
            "\"Doe, Jane\" <jane@example.test>; bob@example.test\n"
        )

        assertEquals(
            listOf("jane@example.test", "bob@example.test"),
            field.addresses.map { it.address }
        )
        assertEquals("Doe, Jane", field.addresses.first().name)
    }

    @Test
    fun `text that is not an address becomes an invalid chip instead of being lost`() {
        val field = RecipientFields.typed(empty, "nobody, ana@example.test,")

        assertEquals(listOf(false, true), field.chips.map { it.valid })
        assertEquals("nobody", field.chips.first().text)
        assertTrue(field.hasInvalid)
        assertEquals(listOf("ana@example.test"), field.addresses.map { it.address })
    }

    @Test
    fun `the same address is kept once whatever its case`() {
        val field = RecipientFields.typed(
            RecipientFieldState.of(listOf(MailAddress("ana@example.test"))),
            "ANA@example.test,"
        )

        assertEquals(1, field.chips.size)
    }

    @Test
    fun `commit turns the pending text into chips and clears it`() {
        val field = RecipientFields.commit(empty.copy(input = "ana@example.test"))

        assertEquals(1, field.chips.size)
        assertEquals("", field.input)
        assertEquals(empty, RecipientFields.commit(empty))
    }

    @Test
    fun `picking a suggestion adds a chip with its name and clears the text`() {
        val field = RecipientFields.pick(
            empty.copy(input = "an"),
            MailAddress("ana@example.test", "Ana")
        )

        assertEquals("Ana <ana@example.test>", field.chips.single().text)
        assertEquals("", field.input)
        assertFalse(field.hasInvalid)
    }

    @Test
    fun `removing a chip by position ignores positions that do not exist`() {
        val field = RecipientFieldState.of(
            listOf(MailAddress("a@example.test"), MailAddress("b@example.test"))
        )

        assertEquals(
            listOf("b@example.test"),
            RecipientFields.remove(field, 0).addresses.map { it.address }
        )
        assertEquals(field, RecipientFields.remove(field, 5))
        assertEquals(field, RecipientFields.remove(field, -1))
    }

    @Test
    fun `chips never show addresses in toString`() {
        val field = RecipientFieldState.of(listOf(MailAddress("ana@example.test")))

        assertFalse("ana" in field.toString() || "ana" in field.chips.single().toString())
    }
}
