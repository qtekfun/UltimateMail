// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** In-memory [PreferenceStore] that announces changes like SharedPreferences does. */
class FakePreferenceStore : PreferenceStore {
    val values = mutableMapOf<String, Any>()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    override fun getString(key: String) = values[key] as? String

    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
        changed.tryEmit(Unit)
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
        changed.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changed
}
