// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.ui.system.aggressiveBatteryVendor
import com.qtekfun.ultimatemail.ui.system.isIgnoringBatteryOptimizations
import com.qtekfun.ultimatemail.ui.system.openAppDetails
import com.qtekfun.ultimatemail.ui.system.rememberCheck
import com.qtekfun.ultimatemail.ui.system.requestIgnoreBatteryOptimizations

/**
 * What the sync needs from the system to keep running with the screen off, checked live: a notice
 * with a button when the battery exemption is missing, and, for phone makers that close background
 * apps on their own, the steps their battery manager needs.
 */
@Composable
internal fun BackgroundSection() {
    val context = LocalContext.current
    val battery by rememberCheck { isIgnoringBatteryOptimizations(context) }
    val vendor = remember { aggressiveBatteryVendor() }
    SectionHeader(stringResource(R.string.settings_section_background))
    if (!battery) {
        Notice(R.string.background_battery_off, R.string.background_allow) {
            requestIgnoreBatteryOptimizations(context)
        }
    }
    if (vendor != null) {
        Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.background_vendor_title, vendor),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.background_vendor_steps),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { openAppDetails(context) }) {
                Text(stringResource(R.string.background_open_app_settings))
            }
        }
    }
    if (battery && vendor == null) {
        SettingsSummary(stringResource(R.string.background_all_ok))
    }
}

@Composable
private fun Notice(message: Int, action: Int, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}
