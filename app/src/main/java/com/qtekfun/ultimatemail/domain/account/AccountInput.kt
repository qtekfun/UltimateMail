// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity

/** Host, port and security of one server. */
data class ServerEndpoint(val host: String, val port: Int, val security: ConnectionSecurity)

/** What the user enters (or accepts from the autodetection) to add an account. */
data class AccountInput(
    val email: String,
    val displayName: String,
    val username: String,
    val authType: AuthType,
    val imap: ServerEndpoint,
    val smtp: ServerEndpoint,
    val credentials: AccountCredentials
)

/** Suggested settings for an e-mail domain. */
data class ServerSuggestion(
    val authType: AuthType,
    val imap: ServerEndpoint,
    val smtp: ServerEndpoint
)
