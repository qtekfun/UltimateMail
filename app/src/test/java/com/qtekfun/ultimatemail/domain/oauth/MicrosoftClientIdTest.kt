// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MicrosoftClientIdTest {
    private val valid = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"

    @Test
    fun `accepts a GUID in any case, with whitespace from pasting`() {
        assertTrue(MicrosoftClientId.isValid(valid))
        assertTrue(MicrosoftClientId.isValid("  ${valid.uppercase()}\n"))
        assertEquals(valid, MicrosoftClientId.normalize(" $valid "))
    }

    @Test
    fun `the empty text means no client ID and is allowed`() {
        assertTrue(MicrosoftClientId.isValid(""))
        assertTrue(MicrosoftClientId.isValid("  "))
    }

    @Test
    fun `rejects text that is not a GUID`() {
        assertFalse(MicrosoftClientId.isValid("abc"))
        assertFalse(MicrosoftClientId.isValid("0a1b2c3d4e5f6789abcdef0123456789"))
        assertFalse(MicrosoftClientId.isValid("0a1b2c3d-4e5f-6789-abcd-ef012345678g"))
        assertFalse(MicrosoftClientId.isValid("123-abc.apps.googleusercontent.com"))
    }
}
