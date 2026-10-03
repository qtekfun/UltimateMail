// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.oauth.OAuthProviders

/** Decides whether an account is picked from as labels (Gmail) or as folders (the rest). */
object PickerModeDetector {
    /** Only the host table is used, so no client ID is needed. */
    private val hosts = OAuthProviders(googleClientId = "", applicationId = "")

    /**
     * An account is a Gmail one when it signs in with Google, when its IMAP host is one of
     * Google's (the same table as the OAuth setup), or when the server announced labels
     * ([hasLabelFolders]: some folder row has `isLabel`).
     */
    fun detect(authType: AuthType, imapHost: String, hasLabelFolders: Boolean): PickerMode {
        val gmail = authType == AuthType.OAUTH_GOOGLE ||
            hosts.authTypeFor(imapHost) == AuthType.OAUTH_GOOGLE ||
            hasLabelFolders
        return if (gmail) PickerMode.LABELS else PickerMode.FOLDERS
    }
}
