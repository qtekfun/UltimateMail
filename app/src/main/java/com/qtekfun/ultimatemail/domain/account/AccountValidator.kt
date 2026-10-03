// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import java.util.Locale
import javax.inject.Inject

/** A problem with the data entered to add an account; the UI maps each one to a message. */
sealed interface AccountInputError {
    data object InvalidEmail : AccountInputError

    data object InvalidUsername : AccountInputError

    data class InvalidHost(val server: Server) : AccountInputError

    data class InvalidPort(val server: Server) : AccountInputError

    /** A password account without a password, or an OAuth account without tokens. */
    data object MissingCredentials : AccountInputError

    data object DuplicateAccount : AccountInputError

    enum class Server { IMAP, SMTP }
}

/** Checks the input of the account setup (RF-01: invalid server -> clear, actionable error). */
class AccountValidator @Inject constructor() {
    fun validate(input: AccountInput): List<AccountInputError> = buildList {
        if (domainOf(input.email) == null) add(AccountInputError.InvalidEmail)
        if (input.username.isBlank()) add(AccountInputError.InvalidUsername)
        checkEndpoint(input.imap, AccountInputError.Server.IMAP)
        checkEndpoint(input.smtp, AccountInputError.Server.SMTP)
        if (!hasCredentials(input)) add(AccountInputError.MissingCredentials)
    }

    private fun MutableList<AccountInputError>.checkEndpoint(
        endpoint: ServerEndpoint,
        server: AccountInputError.Server
    ) {
        if (!HOST.matches(endpoint.host)) add(AccountInputError.InvalidHost(server))
        if (endpoint.port !in PORTS) add(AccountInputError.InvalidPort(server))
    }

    private fun hasCredentials(input: AccountInput): Boolean = when (input.authType) {
        AuthType.PASSWORD -> !input.credentials.password.isNullOrEmpty()
        AuthType.OAUTH_GOOGLE, AuthType.OAUTH_MICROSOFT -> input.credentials.oauth != null
    }

    companion object {
        private val PORTS = 1..65535
        private val EMAIL = Regex("""[^\s@<>()",;:]+@([A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+)""")
        private val HOST = Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*""")

        /** The lower-case domain of a plausible address, or null if [email] is not one. */
        fun domainOf(email: String): String? =
            EMAIL.matchEntire(email.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }
}
