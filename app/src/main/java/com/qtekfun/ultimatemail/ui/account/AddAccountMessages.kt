// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import androidx.annotation.StringRes
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure

/** The input fields of the add-account form. [GENERAL] is for errors about the form as a whole. */
enum class FormField {
    EMAIL,
    USERNAME,
    PASSWORD,
    IMAP_HOST,
    IMAP_PORT,
    SMTP_HOST,
    SMTP_PORT,
    GENERAL;

    /** True for the fields that live in the collapsed "advanced" section. */
    val isAdvanced: Boolean
        get() = this != EMAIL && this != PASSWORD && this != GENERAL
}

/** A validation message for one field. */
data class FieldError(val field: FormField, @StringRes val message: Int)

fun AccountInputError.toFieldError(): FieldError = when (this) {
    AccountInputError.InvalidEmail -> FieldError(FormField.EMAIL, R.string.error_email_invalid)

    AccountInputError.InvalidUsername ->
        FieldError(FormField.USERNAME, R.string.error_username_invalid)

    is AccountInputError.InvalidHost -> when (server) {
        AccountInputError.Server.IMAP ->
            FieldError(FormField.IMAP_HOST, R.string.error_host_invalid)

        AccountInputError.Server.SMTP ->
            FieldError(FormField.SMTP_HOST, R.string.error_host_invalid)
    }

    is AccountInputError.InvalidPort -> when (server) {
        AccountInputError.Server.IMAP ->
            FieldError(FormField.IMAP_PORT, R.string.error_port_invalid)

        AccountInputError.Server.SMTP ->
            FieldError(FormField.SMTP_PORT, R.string.error_port_invalid)
    }

    AccountInputError.MissingCredentials ->
        FieldError(FormField.PASSWORD, R.string.error_password_missing)

    AccountInputError.DuplicateAccount ->
        FieldError(FormField.GENERAL, R.string.error_account_duplicate)
}

/** Actionable text for a failed connection test: what went wrong and what to check. */
@StringRes
fun AddAccountFailure.toMessage(): Int = when (this) {
    is AddAccountFailure.Connection -> reason.toMessage()
    AddAccountFailure.TestUnavailable -> R.string.error_test_unavailable
    AddAccountFailure.StorageFailed -> R.string.error_storage_failed
}

@StringRes
fun ConnectionFailure.toMessage(): Int = when (this) {
    ConnectionFailure.AUTHENTICATION_FAILED -> R.string.error_connection_auth
    ConnectionFailure.HOST_UNREACHABLE -> R.string.error_connection_unreachable
    ConnectionFailure.TLS_ERROR -> R.string.error_connection_tls
    ConnectionFailure.TIMEOUT -> R.string.error_connection_timeout
    ConnectionFailure.UNKNOWN -> R.string.error_connection_unknown
}
