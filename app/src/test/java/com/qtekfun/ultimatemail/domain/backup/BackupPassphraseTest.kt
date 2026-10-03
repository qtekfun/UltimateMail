// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupPassphraseTest {
    private fun check(pass: String, confirm: String = pass, credentials: Boolean = false) =
        BackupPassphrase.check(pass.toCharArray(), confirm.toCharArray(), credentials)

    private fun strength(text: String) = BackupPassphrase.strength(text.toCharArray())

    @Test
    fun `a passphrase is mandatory`() {
        assertEquals(PassphraseIssue.EMPTY, check(""))
        assertEquals(PassphraseIssue.EMPTY, check("", credentials = true))
    }

    @Test
    fun `without credentials any non empty passphrase that matches is accepted`() {
        assertNull(check("abc"))
    }

    @Test
    fun `with credentials at least eight characters are needed`() {
        assertEquals(PassphraseIssue.TOO_SHORT, check("1234567", credentials = true))
        assertNull(check("12345678", credentials = true))
    }

    @Test
    fun `the confirmation must match exactly`() {
        assertEquals(PassphraseIssue.MISMATCH, check("secret-one", "secret-two"))
        assertEquals(PassphraseIssue.MISMATCH, check("secret-one", "Secret-one"))
        assertEquals(PassphraseIssue.MISMATCH, check("secret-one", ""))
    }

    @Test
    fun `too short is reported before a mismatch`() {
        assertEquals(PassphraseIssue.TOO_SHORT, check("abc", "abd", credentials = true))
    }

    @Test
    fun `strength grows with length and variety`() {
        assertEquals(PassphraseStrength.NONE, strength(""))
        assertEquals(PassphraseStrength.WEAK, strength("abc12"))
        assertEquals(PassphraseStrength.WEAK, strength("aaaaaaaaaaaaaaaaaaaa"))
        assertEquals(PassphraseStrength.FAIR, strength("abcdefgh"))
        assertEquals(PassphraseStrength.FAIR, strength("abcdefghijkl"))
        assertEquals(PassphraseStrength.STRONG, strength("Abcdefgh1jkl"))
        assertEquals(PassphraseStrength.STRONG, strength("correct horse battery"))
    }
}
