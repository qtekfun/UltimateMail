// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.system

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// What the system must allow for the sync to keep running with the screen off (RF-10). The
// periodic sync is a WorkManager job that Android stops, or whose network it cuts, when the app is
// subject to battery optimisation; phone makers add their own battery manager on top.

/** True when the system no longer applies battery optimisation (Doze, app standby) to this app. */
fun isIgnoringBatteryOptimizations(context: Context): Boolean = context
    .getSystemService(PowerManager::class.java)
    ?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * Asks the system to exempt the app; falls back to the list of exemptions when the direct dialog
 * is not available. Play restricts the permission behind it, F-Droid does not, and a mail client
 * that must sync with the screen off is the case it exists for.
 */
@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri()
    )
    if (!launch(context, direct)) {
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/** The app's own page in the system settings: battery usage, autostart, permissions. */
fun openAppDetails(context: Context) {
    launch(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        )
    )
}

private fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    // Nothing on this device handles it: the user can still reach the settings by hand.
    false
}

/**
 * The name of the phone maker when it is known for closing background apps harder than Android
 * itself does (the system exemption is not enough there: the maker's own battery manager must be
 * told too); otherwise null.
 */
fun aggressiveBatteryVendor(manufacturer: String = Build.MANUFACTURER): String? =
    when (manufacturer.lowercase()) {
        "oppo" -> "OPPO"
        "realme" -> "realme"
        "oneplus" -> "OnePlus"
        "xiaomi" -> "Xiaomi"
        "redmi" -> "Redmi"
        "poco" -> "POCO"
        "huawei" -> "Huawei"
        "honor" -> "Honor"
        "vivo" -> "vivo"
        "iqoo" -> "iQOO"
        "samsung" -> "Samsung"
        else -> null
    }

/** The value of [check], read again whenever the app comes back to the foreground. */
@Composable
fun rememberCheck(check: () -> Boolean): State<Boolean> {
    val state = remember { mutableStateOf(check()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = check()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** [isIgnoringBatteryOptimizations] as live state. */
@Composable
fun rememberIgnoringBatteryOptimizations(): State<Boolean> {
    val context = LocalContext.current
    return rememberCheck { isIgnoringBatteryOptimizations(context) }
}
