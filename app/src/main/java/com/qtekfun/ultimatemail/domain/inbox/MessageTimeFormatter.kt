// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Formats the time of a message for a list: today as the time of day, the last six days as the
 * weekday, anything older (and anything in the future) as a date. Everything that varies is
 * injected so it can be tested: the [clock] (now), the [zone], the [locale] and whether the
 * user wants a 24-hour clock ([is24Hour]).
 */
class MessageTimeFormatter(
    private val clock: Clock,
    private val zone: ZoneId,
    locale: Locale,
    is24Hour: Boolean
) {
    private val timeFormatter = DateTimeFormatter.ofPattern(
        if (is24Hour) "HH:mm" else "h:mm a",
        locale
    )
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE", locale)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)

    /** The short text of a list row. */
    fun format(instant: Instant): String {
        val moment = instant.atZone(zone)
        return when (daysAgo(moment.toLocalDate())) {
            0L -> timeFormatter.format(moment)
            in 1 until WEEKDAY_DAYS -> weekdayFormatter.format(moment)
            else -> dateFormatter.format(moment)
        }
    }

    /** A longer text for screen readers: the date and the time, or just the time for today. */
    fun formatSpoken(instant: Instant): String {
        val moment = instant.atZone(zone)
        return if (daysAgo(moment.toLocalDate()) == 0L) {
            timeFormatter.format(moment)
        } else {
            dateFormatter.format(moment) + ", " + timeFormatter.format(moment)
        }
    }

    private fun daysAgo(date: LocalDate): Long =
        ChronoUnit.DAYS.between(date, clock.instant().atZone(zone).toLocalDate())

    private companion object {
        /** Days (yesterday to six days ago) that still show the weekday name. */
        const val WEEKDAY_DAYS = 7L
    }
}
