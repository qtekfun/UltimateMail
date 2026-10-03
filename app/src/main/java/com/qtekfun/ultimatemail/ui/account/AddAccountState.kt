// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ServerEndpoint
import com.qtekfun.ultimatemail.domain.account.ServerSuggestion

/** Extra help shown under the form for providers that need more than a password. */
enum class ProviderHint { NONE, GMAIL, MICROSOFT }

/** The text inputs of the form. */
enum class FormInput {
    EMAIL,
    PASSWORD,
    DISPLAY_NAME,
    USERNAME,
    IMAP_HOST,
    IMAP_PORT,
    SMTP_HOST,
    SMTP_PORT
}

/** Where the add-account flow is: waiting for input or busy. */
enum class AddAccountProgress { IDLE, TESTING, SAVING }

/** Why the last attempt to add the account did not work, apart from invalid fields. */
sealed interface AddAccountFailure {
    data class Connection(val reason: ConnectionFailure) : AddAccountFailure

    /** This build has no connection tester, so nothing could be verified. */
    data object TestUnavailable : AddAccountFailure

    data object StorageFailed : AddAccountFailure
}

/** Everything the add-account screen shows; the form fields are kept as typed. */
data class AddAccountState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val advancedExpanded: Boolean = false,
    /** Empty means "same as the address". */
    val username: String = "",
    val imapHost: String = "",
    val imapPort: String = DEFAULT_IMAP_PORT.toString(),
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: String = DEFAULT_SMTP_PORT.toString(),
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
    /** True once the user edited a server field: autodetection then leaves them alone. */
    val serversEdited: Boolean = false,
    val authType: AuthType = AuthType.PASSWORD,
    /**
     * The sign-in methods offered. Only passwords for now; the Google and Microsoft sign-in will
     * be added here when their flow is wired in, and the screen then shows the choice.
     */
    val availableAuthTypes: List<AuthType> = listOf(AuthType.PASSWORD),
    val hint: ProviderHint = ProviderHint.NONE,
    val fieldErrors: List<FieldError> = emptyList(),
    val progress: AddAccountProgress = AddAccountProgress.IDLE,
    val failure: AddAccountFailure? = null
) {
    val busy: Boolean get() = progress != AddAccountProgress.IDLE

    fun errorFor(field: FormField): FieldError? = fieldErrors.firstOrNull { it.field == field }

    /** The domain input for this form; unparseable ports become 0, which validation rejects. */
    fun toInput(): AccountInput {
        val address = email.trim()
        return AccountInput(
            email = address,
            displayName = displayName.trim(),
            username = username.trim().ifEmpty { address },
            authType = authType,
            imap = ServerEndpoint(imapHost.trim(), portOf(imapPort), imapSecurity),
            smtp = ServerEndpoint(smtpHost.trim(), portOf(smtpPort), smtpSecurity),
            credentials = AccountCredentials(password = password)
        )
    }

    /** Typing in a server field stops the autodetection from overwriting the servers. */
    fun withText(input: FormInput, value: String): AddAccountState = when (input) {
        FormInput.EMAIL -> copy(email = value)
        FormInput.PASSWORD -> copy(password = value)
        FormInput.DISPLAY_NAME -> copy(displayName = value)
        FormInput.USERNAME -> copy(username = value)
        FormInput.IMAP_HOST -> copy(imapHost = value, serversEdited = true)
        FormInput.IMAP_PORT -> copy(imapPort = value, serversEdited = true)
        FormInput.SMTP_HOST -> copy(smtpHost = value, serversEdited = true)
        FormInput.SMTP_PORT -> copy(smtpPort = value, serversEdited = true)
    }

    fun withSecurity(server: AccountInputError.Server, value: ConnectionSecurity) = when (server) {
        AccountInputError.Server.IMAP -> copy(imapSecurity = value, serversEdited = true)
        AccountInputError.Server.SMTP -> copy(smtpSecurity = value, serversEdited = true)
    }

    /** Fills the servers from the autodetection and picks the hint; no suggestion clears it. */
    fun withSuggestion(suggestion: ServerSuggestion?): AddAccountState {
        if (suggestion == null) return copy(hint = ProviderHint.NONE)
        return copy(
            imapHost = suggestion.imap.host,
            imapPort = suggestion.imap.port.toString(),
            imapSecurity = suggestion.imap.security,
            smtpHost = suggestion.smtp.host,
            smtpPort = suggestion.smtp.port.toString(),
            smtpSecurity = suggestion.smtp.security,
            hint = when (suggestion.authType) {
                AuthType.OAUTH_GOOGLE -> ProviderHint.GMAIL
                AuthType.OAUTH_MICROSOFT -> ProviderHint.MICROSOFT
                AuthType.PASSWORD -> ProviderHint.NONE
            }
        )
    }

    /** Typing clears the messages about the previous attempt. */
    fun edited() = copy(fieldErrors = emptyList(), failure = null)

    private fun portOf(text: String): Int = text.trim().toIntOrNull() ?: 0

    companion object {
        const val DEFAULT_IMAP_PORT = 993
        const val DEFAULT_SMTP_PORT = 587
    }
}
