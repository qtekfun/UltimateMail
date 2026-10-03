// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * The few key/value operations the settings need. [SettingsRepository] only knows this
 * interface, so it is tested with an in-memory store; the real one is [SharedPreferencesStore].
 */
interface PreferenceStore {
    fun getString(key: String): String?

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putString(key: String, value: String)

    fun putBoolean(key: String, value: Boolean)

    /** Emits each time any value changes (not for the current state). */
    fun changes(): Flow<Unit>
}
