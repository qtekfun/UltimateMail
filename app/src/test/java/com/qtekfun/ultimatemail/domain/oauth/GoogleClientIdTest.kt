// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoogleClientIdTest {
    private val valid = "123456789012-abc123def456.apps.googleusercontent.com"

    @Test
    fun `accepts a Google client ID, with surrounding whitespace from pasting`() {
        assertTrue(GoogleClientId.isValid(valid))
        assertTrue(GoogleClientId.isValid("  $valid\n"))
        assertEquals(valid, GoogleClientId.normalize("  $valid\n"))
    }

    @Test
    fun `the empty text means no client ID and is allowed`() {
        assertTrue(GoogleClientId.isValid(""))
        assertTrue(GoogleClientId.isValid("   "))
    }

    @Test
    fun `rejects text that is not a Google client ID`() {
        assertFalse(GoogleClientId.isValid("abc"))
        assertFalse(GoogleClientId.isValid("123-abc.apps.example.com"))
        assertFalse(GoogleClientId.isValid("123456789012.apps.googleusercontent.com"))
        assertFalse(GoogleClientId.isValid("$valid extra"))
    }
}
