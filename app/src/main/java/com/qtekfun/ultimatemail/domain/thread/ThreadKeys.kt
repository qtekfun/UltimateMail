// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import java.text.Normalizer
import java.util.Locale

/** Normalisation of the header values the threading rules compare. */
internal object ThreadKeys {
    private const val MAX_MESSAGE_ID_LENGTH = 512

    private val prefix = Regex(
        """^[\s\p{Z}]*(?<word>re|fw|fwd|aw|wg|sv|vs|enc|tr|rv|reenvio|reenvío|env)""" +
            """(?:\[\d+]|\(\d+\))?[\s\p{Z}]*:[\s\p{Z}]*""",
        RegexOption.IGNORE_CASE
    )
    private val whitespace = Regex("""[\s\p{Z}]+""")

    /** A subject without its reply and forward prefixes, and whether it had any. */
    data class Subject(val text: String, val hadPrefix: Boolean)

    /**
     * Strips every leading Re:/Fwd:/RV:/AW:... prefix (also numbered, as in RE[2]:), folds
     * compatibility characters, lowercases and collapses whitespace.
     */
    fun subject(raw: String?): Subject {
        var text = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC)
        var hadPrefix = false
        var match = prefix.find(text)
        while (match != null) {
            text = text.substring(match.range.last + 1)
            hadPrefix = true
            match = prefix.find(text)
        }
        val folded = whitespace.replace(text, " ").trim().lowercase(Locale.ROOT)
        return Subject(folded, hadPrefix)
    }

    /**
     * The first reply or forward prefix of [raw] in lowercase ("re", "fwd", "aw", "rv"...), or null
     * when the subject has none. The composer uses it to avoid stacking `Re: Re:`.
     */
    fun leadingPrefix(raw: String?): String? {
        val text = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC)
        return prefix.find(text)?.groups?.get("word")?.value?.lowercase(Locale.ROOT)
    }

    /**
     * A usable Message-ID: angle brackets and surrounding blanks removed, lowercased; null for
     * blanks and for garbage (inner whitespace, brackets, absurd length).
     */
    fun messageId(raw: String?): String? {
        val id = raw.orEmpty().trim().removePrefix("<").removeSuffix(">").trim()
        val valid = id.isNotEmpty() &&
            id.length <= MAX_MESSAGE_ID_LENGTH &&
            id.none { it.isWhitespace() || it == '<' || it == '>' }
        return if (valid) id.lowercase(Locale.ROOT) else null
    }
}
