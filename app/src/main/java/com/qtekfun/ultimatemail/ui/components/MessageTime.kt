// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.qtekfun.ultimatemail.domain.inbox.MessageTimeFormatter
import java.time.Clock
import java.time.ZoneId

/**
 * A [MessageTimeFormatter] for the device: its locale, its time zone, the system clock and the
 * 12/24-hour choice of the user. It is rebuilt when the configuration (locale) changes.
 */
@Composable
fun rememberMessageTimeFormatter(): MessageTimeFormatter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        MessageTimeFormatter(
            clock = Clock.systemDefaultZone(),
            zone = ZoneId.systemDefault(),
            locale = configuration.locales[0],
            is24Hour = DateFormat.is24HourFormat(context)
        )
    }
}
