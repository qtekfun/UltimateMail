// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import java.time.LocalDate

/**
 * A word or a quoted phrase of a search. [prefix] means "starts with": the word being typed, or
 * the value of `from:` / `to:`.
 */
data class SearchTerm(val text: String, val phrase: Boolean = false, val prefix: Boolean = false)

/**
 * What the user asked for, free of any SQL or IMAP syntax (see [SearchQueryParser]). Every part
 * narrows the result: all [terms] must be found, and so on. Nothing here is ever logged.
 *
 * @property terms free words and phrases, searched in subject, sender and cached bodies.
 * @property excluded words and phrases that must not be found (`-word`).
 * @property from, to, subject text of the sender (name or address), of the recipients and of the
 *   subject.
 * @property labels `label:` / `in:` values: a folder name or role, or a Gmail label.
 * @property unread true for `is:unread`, false for `is:read`, null for both.
 * @property after first day included; [before] first day excluded (both in the user's zone).
 */
data class SearchQuery(
    val terms: List<SearchTerm> = emptyList(),
    val excluded: List<SearchTerm> = emptyList(),
    val from: List<SearchTerm> = emptyList(),
    val to: List<SearchTerm> = emptyList(),
    val subject: List<SearchTerm> = emptyList(),
    val labels: List<String> = emptyList(),
    val hasAttachment: Boolean = false,
    val unread: Boolean? = null,
    val starred: Boolean = false,
    val after: LocalDate? = null,
    val before: LocalDate? = null
) {
    /** True when the query asks for nothing: there is nothing to search for. */
    val isEmpty: Boolean
        get() = this == EMPTY

    /** The words and phrases to highlight in the text a search result shows. */
    fun highlightTerms(field: HighlightField): List<SearchTerm> = when (field) {
        HighlightField.SENDER -> terms + from
        HighlightField.SUBJECT -> terms + subject
        HighlightField.SNIPPET -> terms
    }

    companion object {
        val EMPTY = SearchQuery()
    }
}

/** The texts of a result row in which matches are highlighted. */
enum class HighlightField { SENDER, SUBJECT, SNIPPET }
