// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

import com.qtekfun.ultimatemail.domain.signature.ComposeKind
import com.qtekfun.ultimatemail.domain.signature.SignatureEditor
import com.qtekfun.ultimatemail.domain.signature.SignatureSettings

/** The parts of an account the user edits as one form: the name and the signature (RF-08). */
data class AccountProfile(
    val displayName: String,
    val signature: String,
    val signatureEnabled: Boolean,
    val signatureBeforeQuote: Boolean
) {
    /** The signature settings the composer will use for this profile. */
    val signatureSettings: SignatureSettings
        get() = SignatureSettings(signature, signatureEnabled, signatureBeforeQuote)
}

/** Why a profile cannot be saved. */
enum class ProfileError { NAME_TOO_LONG, SIGNATURE_TOO_LONG }

/** Cleaning and checking what the user typed in the account form. */
object ProfileRules {
    const val MAX_NAME_LENGTH = 100
    const val MAX_SIGNATURE_LENGTH = 1_000

    /**
     * The profile as it is stored: line endings are LF, each line and the whole signature lose
     * trailing whitespace, and blank lines before the signature go; the name is trimmed.
     */
    fun normalize(profile: AccountProfile): AccountProfile = profile.copy(
        displayName = profile.displayName.trim(),
        signature = profile.signature.replace("\r\n", "\n").lines()
            .joinToString("\n") { it.trimEnd() }
            .trim('\n')
    )

    /** What is wrong with [profile], measured after [normalize]; empty when it can be saved. */
    fun errors(profile: AccountProfile): Set<ProfileError> {
        val clean = normalize(profile)
        return buildSet {
            if (clean.displayName.length > MAX_NAME_LENGTH) add(ProfileError.NAME_TOO_LONG)
            if (clean.signature.length > MAX_SIGNATURE_LENGTH) {
                add(ProfileError.SIGNATURE_TOO_LONG)
            }
        }
    }
}

/** What a new message and a reply look like with the signature of a profile. */
data class SignaturePreview(val newMessage: String, val reply: String)

/** Builds [SignaturePreview] with the same code the composer uses, so it cannot disagree. */
object SignaturePreviews {
    /** [newBody] and [replyBody] (which ends with a quote) are the sample texts to sign. */
    fun build(profile: AccountProfile, newBody: String, replyBody: String): SignaturePreview {
        val settings = ProfileRules.normalize(profile).signatureSettings
        return SignaturePreview(
            newMessage = SignatureEditor.apply(newBody, ComposeKind.NEW, settings),
            reply = SignatureEditor.apply(replyBody, ComposeKind.REPLY, settings)
        )
    }
}
