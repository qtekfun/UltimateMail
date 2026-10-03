// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

/** Why the passphrase typed for an export cannot be used. */
enum class PassphraseIssue { EMPTY, TOO_SHORT, MISMATCH }

/** A rough idea of how hard a passphrase is to guess; computed on the device, never online. */
enum class PassphraseStrength { NONE, WEAK, FAIR, STRONG }

/** The rules for the passphrase of a backup (RF-12). Arrays are used so callers can wipe them. */
object BackupPassphrase {
    /** With credentials in the file the passphrase is all that protects them. */
    const val MIN_LENGTH_WITH_CREDENTIALS = 8

    private const val FAIR_LENGTH = 8
    private const val STRONG_LENGTH = 12
    private const val LONG_PASSPHRASE = 16
    private const val STRONG_CLASSES = 3

    /** The first problem with [passphrase] and its [confirmation], or null when it is fine. */
    fun check(
        passphrase: CharArray,
        confirmation: CharArray,
        includeCredentials: Boolean
    ): PassphraseIssue? = when {
        passphrase.isEmpty() -> PassphraseIssue.EMPTY

        includeCredentials && passphrase.size < MIN_LENGTH_WITH_CREDENTIALS ->
            PassphraseIssue.TOO_SHORT

        !passphrase.contentEquals(confirmation) -> PassphraseIssue.MISMATCH

        else -> null
    }

    fun strength(passphrase: CharArray): PassphraseStrength {
        if (passphrase.isEmpty()) return PassphraseStrength.NONE
        val classes = listOf<(Char) -> Boolean>(
            Char::isLowerCase,
            Char::isUpperCase,
            Char::isDigit,
            { char: Char -> !char.isLetterOrDigit() }
        ).count { kind -> passphrase.any(kind) }
        val varied = passphrase.toSet().size > 1
        return when {
            !varied || passphrase.size < FAIR_LENGTH -> PassphraseStrength.WEAK

            passphrase.size >= LONG_PASSPHRASE ||
                (passphrase.size >= STRONG_LENGTH && classes >= STRONG_CLASSES) ->
                PassphraseStrength.STRONG

            else -> PassphraseStrength.FAIR
        }
    }
}
