// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Now" in every test is Saturday 2026-10-03 12:00 UTC. */
class MessageTimeFormatterTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun formatter(
        zone: String = "UTC",
        locale: Locale = Locale.US,
        is24Hour: Boolean = true
    ) = MessageTimeFormatter(clock, ZoneId.of(zone), locale, is24Hour)

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `today shows the time of day in 24 hours`() {
        assertEquals("09:05", formatter().format(at("2026-10-03T09:05:00Z")))
        assertEquals("13:30", formatter().format(at("2026-10-03T13:30:00Z")))
    }

    @Test
    fun `today shows the time of day in 12 hours`() {
        assertEquals("9:05 AM", formatter(is24Hour = false).format(at("2026-10-03T09:05:00Z")))
        assertEquals("1:30 PM", formatter(is24Hour = false).format(at("2026-10-03T13:30:00Z")))
        assertEquals("12:00 AM", formatter(is24Hour = false).format(at("2026-10-03T00:00:00Z")))
    }

    @Test
    fun `midnight starts today and the second before it is yesterday`() {
        assertEquals("00:00", formatter().format(at("2026-10-03T00:00:00Z")))
        assertEquals("Fri", formatter().format(at("2026-10-02T23:59:59Z")))
    }

    @Test
    fun `the last six days show the weekday`() {
        assertEquals("Fri", formatter().format(at("2026-10-02T08:00:00Z")))
        assertEquals("Wed", formatter().format(at("2026-09-30T08:00:00Z")))
        assertEquals("Sun", formatter().format(at("2026-09-27T08:00:00Z")))
    }

    @Test
    fun `seven days ago and older show the date`() {
        assertEquals("Sep 26, 2026", formatter().format(at("2026-09-26T08:00:00Z")))
        assertEquals("Jan 5, 2025", formatter().format(at("2025-01-05T08:00:00Z")))
    }

    @Test
    fun `a message from the future today shows the time and any other future day the date`() {
        assertEquals("23:30", formatter().format(at("2026-10-03T23:30:00Z")))
        assertEquals("Oct 4, 2026", formatter().format(at("2026-10-04T10:00:00Z")))
    }

    @Test
    fun `the day is decided in the time zone of the device`() {
        val instant = at("2026-10-02T15:00:00Z")

        // 00:00 on Oct 3 in Tokyo, where it is already evening; yesterday in UTC.
        assertEquals("Fri", formatter("UTC").format(instant))
        assertEquals("00:00", formatter("Asia/Tokyo").format(instant))
    }

    @Test
    fun `the time is shown in the time zone of the device`() {
        val instant = at("2026-10-03T09:05:00Z")

        assertEquals("09:05", formatter("UTC").format(instant))
        assertEquals("05:05", formatter("America/New_York").format(instant))
        assertEquals("14:35", formatter("Asia/Kolkata").format(instant))
    }

    @Test
    fun `a spanish device gets spanish weekdays and dates`() {
        val spanish = Locale.forLanguageTag("es-ES")

        assertEquals("13:30", formatter(locale = spanish).format(at("2026-10-03T13:30:00Z")))
        assertTrue(formatter(locale = spanish).format(at("2026-10-02T08:00:00Z")).startsWith("vie"))
        val older = formatter(locale = spanish).format(at("2026-09-03T08:00:00Z"))
        assertTrue(older.startsWith("3 ") && older.endsWith("2026"), older)
    }

    @Test
    fun `a british device puts the day before the month`() {
        val older = formatter(locale = Locale.UK).format(at("2026-09-03T08:00:00Z"))

        assertTrue(older.startsWith("3 Sep") && older.endsWith("2026"), older)
    }

    @Test
    fun `a twelve hour clock works in spanish too`() {
        val text = formatter(locale = Locale.forLanguageTag("es-ES"), is24Hour = false)
            .format(at("2026-10-03T13:05:00Z"))

        assertTrue(text.startsWith("1:05"), text)
    }

    @Test
    fun `spoken text is only the time for today`() {
        assertEquals("09:05", formatter().formatSpoken(at("2026-10-03T09:05:00Z")))
    }

    @Test
    fun `spoken text has the full date and the time for other days`() {
        assertEquals("Oct 2, 2026, 08:00", formatter().formatSpoken(at("2026-10-02T08:00:00Z")))
        assertEquals("Jan 5, 2025, 08:00", formatter().formatSpoken(at("2025-01-05T08:00:00Z")))
    }
}
