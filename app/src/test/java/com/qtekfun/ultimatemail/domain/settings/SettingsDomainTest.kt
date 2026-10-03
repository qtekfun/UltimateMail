// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineWindowTest {
    @Test
    fun `each choice is stored as its number of days, and the whole mailbox as null`() {
        assertEquals(listOf(30, 90, 180, 365, null), OfflineWindow.entries.map { it.days })
    }

    @Test
    fun `stored days map back to their choice`() {
        OfflineWindow.entries.forEach { assertEquals(it, OfflineWindow.fromDays(it.days)) }
    }

    @Test
    fun `the default of a new account is the 90 day choice`() {
        assertEquals(
            OfflineWindow.DAYS_90,
            OfflineWindow.fromDays(AccountEntity.DEFAULT_OFFLINE_WINDOW_DAYS)
        )
    }

    @Test
    fun `days that are not a choice show as the closest one`() {
        assertEquals(OfflineWindow.DAYS_30, OfflineWindow.fromDays(1))
        assertEquals(OfflineWindow.DAYS_90, OfflineWindow.fromDays(100))
        assertEquals(OfflineWindow.DAYS_180, OfflineWindow.fromDays(200))
        assertEquals(OfflineWindow.YEAR, OfflineWindow.fromDays(10_000))
    }
}

class AppLanguageTest {
    @Test
    fun `no app locale follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
    }

    @Test
    fun `the first locale decides, with or without a region`() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags("es"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags("es-ES,en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("EN, es"))
    }

    @Test
    fun `an unsupported language follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("fr-FR"))
    }

    @Test
    fun `tags round-trip`() {
        AppLanguage.entries.forEach {
            assertEquals(it, AppLanguage.fromTags(it.tag.orEmpty()))
        }
    }
}

class ProfileRulesTest {
    private fun profile(name: String = "Ana", signature: String = "") =
        AccountProfile(name, signature, signatureEnabled = true, signatureBeforeQuote = true)

    @Test
    fun `trailing whitespace is trimmed from each line and from the signature`() {
        val clean = ProfileRules.normalize(profile(signature = "Ana  \r\nMarketing \t\n\n  \n"))

        assertEquals("Ana\nMarketing", clean.signature)
    }

    @Test
    fun `blank lines before the signature go but indentation stays`() {
        val clean = ProfileRules.normalize(profile(signature = "\n\n  Ana\n    Lead"))

        assertEquals("  Ana\n    Lead", clean.signature)
    }

    @Test
    fun `the name is trimmed`() {
        assertEquals("Ana", ProfileRules.normalize(profile(name = "  Ana ")).displayName)
    }

    @Test
    fun `a signature at the limit is valid and one more character is not`() {
        val limit = "x".repeat(ProfileRules.MAX_SIGNATURE_LENGTH)

        assertTrue(ProfileRules.errors(profile(signature = limit)).isEmpty())
        assertEquals(
            setOf(ProfileError.SIGNATURE_TOO_LONG),
            ProfileRules.errors(profile(signature = limit + "x"))
        )
    }

    @Test
    fun `trailing whitespace does not count against the limit`() {
        val text = "x".repeat(ProfileRules.MAX_SIGNATURE_LENGTH) + "   \n\n"

        assertTrue(ProfileRules.errors(profile(signature = text)).isEmpty())
    }

    @Test
    fun `a name at the limit is valid and a longer one is reported`() {
        val limit = "n".repeat(ProfileRules.MAX_NAME_LENGTH)

        assertTrue(ProfileRules.errors(profile(name = limit)).isEmpty())
        assertEquals(
            setOf(ProfileError.NAME_TOO_LONG),
            ProfileRules.errors(profile(name = limit + "n"))
        )
    }

    @Test
    fun `both errors can be reported together`() {
        val bad = profile(
            name = "n".repeat(ProfileRules.MAX_NAME_LENGTH + 1),
            signature = "x".repeat(ProfileRules.MAX_SIGNATURE_LENGTH + 1)
        )

        assertEquals(
            setOf(ProfileError.NAME_TOO_LONG, ProfileError.SIGNATURE_TOO_LONG),
            ProfileRules.errors(bad)
        )
    }
}

class SignaturePreviewsTest {
    private val newBody = "Hi,\n\nSee you.\n"
    private val replyBody = "Sounds good.\n\nOn Monday, Sam wrote:\n> Meeting?\n"

    private fun profile(
        signature: String = "Ana\nAcme",
        enabled: Boolean = true,
        beforeQuote: Boolean = true
    ) = AccountProfile("Ana", signature, enabled, beforeQuote)

    @Test
    fun `a new message gets the signature at the end`() {
        val preview = SignaturePreviews.build(profile(), newBody, replyBody)

        assertEquals("Hi,\n\nSee you.\n\n-- \nAna\nAcme", preview.newMessage)
    }

    @Test
    fun `a reply gets it above the quote by default`() {
        val preview = SignaturePreviews.build(profile(), newBody, replyBody)

        assertEquals(
            "Sounds good.\n\n-- \nAna\nAcme\n\nOn Monday, Sam wrote:\n> Meeting?\n",
            preview.reply
        )
    }

    @Test
    fun `a reply gets it below the quote when asked, and a new message is not affected`() {
        val preview = SignaturePreviews.build(profile(beforeQuote = false), newBody, replyBody)

        assertEquals("Hi,\n\nSee you.\n\n-- \nAna\nAcme", preview.newMessage)
        assertEquals(
            "Sounds good.\n\nOn Monday, Sam wrote:\n> Meeting?\n\n-- \nAna\nAcme",
            preview.reply
        )
    }

    @Test
    fun `a disabled or blank signature leaves the samples as they are`() {
        listOf(profile(enabled = false), profile(signature = "  \n ")).forEach {
            val preview = SignaturePreviews.build(it, newBody, replyBody)

            assertEquals(newBody, preview.newMessage)
            assertEquals(replyBody, preview.reply)
        }
    }

    @Test
    fun `the preview shows the signature as it will be stored`() {
        val preview = SignaturePreviews.build(profile(signature = "Ana   \n\n"), newBody, replyBody)

        assertEquals("Hi,\n\nSee you.\n\n-- \nAna", preview.newMessage)
    }
}
