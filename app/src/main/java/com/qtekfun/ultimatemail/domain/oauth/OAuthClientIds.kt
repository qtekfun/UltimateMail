// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.oauth

/** The OAuth client IDs the user entered, kept on the device (SPEC §9). They are public values. */
interface OAuthClientIds {
    /** The saved Google client ID, or null when none was entered. */
    fun google(): String?

    /** The saved Microsoft client ID, or null when none was entered. */
    fun microsoft(): String?

    /** Saves the Google client ID; an empty text removes it. */
    fun setGoogle(clientId: String)

    /** Saves the Microsoft client ID; an empty text removes it. */
    fun setMicrosoft(clientId: String)
}
