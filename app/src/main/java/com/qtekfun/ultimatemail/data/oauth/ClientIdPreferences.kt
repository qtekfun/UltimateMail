// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.oauth

import android.content.Context
import androidx.core.content.edit
import com.qtekfun.ultimatemail.domain.oauth.GoogleClientId
import com.qtekfun.ultimatemail.domain.oauth.MicrosoftClientId
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds

/**
 * The OAuth client IDs the user entered, kept on the device (SPEC §9). They are public, not
 * secrets; Android backup is off for this app, so they never leave the phone.
 */
class ClientIdPreferences(context: Context) : OAuthClientIds {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun google(): String? = read(GOOGLE_KEY)

    override fun microsoft(): String? = read(MICROSOFT_KEY)

    override fun setGoogle(clientId: String) {
        preferences.edit { putString(GOOGLE_KEY, GoogleClientId.normalize(clientId)) }
    }

    override fun setMicrosoft(clientId: String) {
        preferences.edit { putString(MICROSOFT_KEY, MicrosoftClientId.normalize(clientId)) }
    }

    private fun read(key: String): String? =
        preferences.getString(key, null)?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FILE = "oauth_client_ids"
        const val GOOGLE_KEY = "google"
        const val MICROSOFT_KEY = "microsoft"
    }
}
