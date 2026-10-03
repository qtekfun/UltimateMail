// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.search

import java.time.LocalDate

/** The date chip of the search screen. */
sealed interface DateFilter {
    /** Stable text form for saved state. */
    val key: String

    data object AnyTime : DateFilter {
        override val key = "any"
    }

    data object Last7Days : DateFilter {
        override val key = "7d"
    }

    data object Last30Days : DateFilter {
        override val key = "30d"
    }

    data object LastYear : DateFilter {
        override val key = "1y"
    }

    /** Both days included; either side may be open. */
    data class Custom(val from: LocalDate?, val to: LocalDate?) : DateFilter {
        override val key = "custom/${from ?: ""}/${to ?: ""}"
    }

    companion object {
        private const val WEEK = 7L
        private const val MONTH = 30L
        private const val CUSTOM_KEY_PARTS = 3

        /** The filter for a [key] of [DateFilter.key]; [AnyTime] when it is not one. */
        fun fromKey(key: String?): DateFilter = when {
            key == Last7Days.key -> Last7Days
            key == Last30Days.key -> Last30Days
            key == LastYear.key -> LastYear
            key != null && key.startsWith("custom/") -> customFromKey(key)
            else -> AnyTime
        }

        private fun customFromKey(key: String): DateFilter {
            val parts = key.split('/')
            val from = parts.getOrNull(1)?.let(SearchQueryParser::date)
            val to = parts.getOrNull(2)?.let(SearchQueryParser::date)
            val valid = parts.size == CUSTOM_KEY_PARTS && (from != null || to != null)
            return if (valid) Custom(from, to) else AnyTime
        }

        internal fun lastDays(today: LocalDate, filter: DateFilter): LocalDate? = when (filter) {
            Last7Days -> today.minusDays(WEEK - 1)
            Last30Days -> today.minusDays(MONTH - 1)
            LastYear -> today.minusYears(1)
            else -> null
        }
    }
}

/** The chips under the search field; each one narrows the search like the matching operator. */
data class SearchFilters(
    val unread: Boolean = false,
    val starred: Boolean = false,
    val withAttachments: Boolean = false,
    val date: DateFilter = DateFilter.AnyTime
) {
    val isEmpty: Boolean get() = this == NONE

    /**
     * [query] narrowed by these chips. [today] fixes what "last 7 days" means. A chip beats the
     * same thing typed in the field when they disagree (the Unread chip over `is:read`); two
     * date limits combine to the narrower one.
     */
    fun applyTo(query: SearchQuery, today: LocalDate): SearchQuery {
        val (after, before) = dateLimits(today)
        return query.copy(
            unread = if (unread) true else query.unread,
            starred = query.starred || starred,
            hasAttachment = query.hasAttachment || withAttachments,
            after = latest(query.after, after),
            before = earliest(query.before, before)
        )
    }

    /** First day included and first day excluded for the date chip. */
    private fun dateLimits(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (date) {
        DateFilter.AnyTime -> null to null
        is DateFilter.Custom -> date.from to date.to?.plusDays(1)
        else -> DateFilter.lastDays(today, date) to null
    }

    private fun latest(a: LocalDate?, b: LocalDate?) = listOfNotNull(a, b).maxOrNull()

    private fun earliest(a: LocalDate?, b: LocalDate?) = listOfNotNull(a, b).minOrNull()

    companion object {
        val NONE = SearchFilters()
    }
}
