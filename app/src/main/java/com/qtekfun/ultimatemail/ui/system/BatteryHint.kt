// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.system

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.R

/**
 * Asks once, after the first account exists, to let the sync run with the screen off. Skipped (and
 * remembered as done) when the exemption is already granted; "Not now" is final too, since the
 * Settings screen keeps the same choice reachable.
 */
@Composable
fun BatteryHintHost(viewModel: BatteryHintViewModel) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exempt = isIgnoringBatteryOptimizations(context)
    LaunchedEffect(pending, exempt) {
        if (pending && exempt) viewModel.markDone()
    }
    if (!pending || exempt) return
    AlertDialog(
        onDismissRequest = viewModel::markDone,
        title = { Text(stringResource(R.string.battery_hint_title)) },
        text = { Text(stringResource(R.string.battery_hint_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.markDone()
                    requestIgnoreBatteryOptimizations(context)
                }
            ) { Text(stringResource(R.string.battery_hint_allow)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::markDone) {
                Text(stringResource(R.string.battery_hint_later))
            }
        }
    )
}
