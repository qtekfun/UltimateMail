// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.oauth

import android.content.Context
import androidx.core.content.edit
import com.qtekfun.ultimatemail.domain.oauth.GoogleClientId

/**
 * The OAuth client ID the user entered, kept on the device (SPEC §9). It is public, not a
 * secret; Android backup is off for this app, so it never leaves the phone.
 */
class ClientIdPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The saved Google client ID, or null when the user has not entered one. */
    fun google(): String? = preferences.getString(GOOGLE_KEY, null)?.takeIf { it.isNotEmpty() }

    /** Saves the Google client ID; an empty text removes it. */
    fun setGoogle(clientId: String) {
        preferences.edit { putString(GOOGLE_KEY, GoogleClientId.normalize(clientId)) }
    }

    private companion object {
        const val FILE = "oauth_client_ids"
        const val GOOGLE_KEY = "google"
    }
}
