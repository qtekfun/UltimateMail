// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.account

import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.account.AccountInputError
import com.qtekfun.ultimatemail.domain.account.AccountInputError.Server
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddAccountMessagesTest {
    @Test
    fun `every input error lands on its field with its message`() {
        val expected = mapOf(
            AccountInputError.InvalidEmail to
                FieldError(FormField.EMAIL, R.string.error_email_invalid),
            AccountInputError.InvalidUsername to
                FieldError(FormField.USERNAME, R.string.error_username_invalid),
            AccountInputError.InvalidHost(Server.IMAP) to
                FieldError(FormField.IMAP_HOST, R.string.error_host_invalid),
            AccountInputError.InvalidHost(Server.SMTP) to
                FieldError(FormField.SMTP_HOST, R.string.error_host_invalid),
            AccountInputError.InvalidPort(Server.IMAP) to
                FieldError(FormField.IMAP_PORT, R.string.error_port_invalid),
            AccountInputError.InvalidPort(Server.SMTP) to
                FieldError(FormField.SMTP_PORT, R.string.error_port_invalid),
            AccountInputError.MissingCredentials to
                FieldError(FormField.PASSWORD, R.string.error_password_missing),
            AccountInputError.DuplicateAccount to
                FieldError(FormField.GENERAL, R.string.error_account_duplicate)
        )

        expected.forEach { (error, field) -> assertEquals(field, error.toFieldError(), "$error") }
    }

    @Test
    fun `each connection failure class has a distinct message`() {
        val messages = ConnectionFailure.entries.map { it.toMessage() }

        assertEquals(ConnectionFailure.entries.size, messages.toSet().size)
        assertEquals(
            R.string.error_connection_auth,
            ConnectionFailure.AUTHENTICATION_FAILED.toMessage()
        )
        assertEquals(R.string.error_connection_tls, ConnectionFailure.TLS_ERROR.toMessage())
    }

    @Test
    fun `failures map to messages`() {
        assertEquals(
            R.string.error_connection_timeout,
            AddAccountFailure.Connection(ConnectionFailure.TIMEOUT).toMessage()
        )
        assertEquals(R.string.error_test_unavailable, AddAccountFailure.TestUnavailable.toMessage())
        assertEquals(R.string.error_storage_failed, AddAccountFailure.StorageFailed.toMessage())
    }

    @Test
    fun `only the server and username fields are advanced`() {
        assertTrue(FormField.IMAP_HOST.isAdvanced)
        assertTrue(FormField.USERNAME.isAdvanced)
        assertFalse(FormField.EMAIL.isAdvanced)
        assertFalse(FormField.PASSWORD.isAdvanced)
        assertFalse(FormField.GENERAL.isAdvanced)
    }
}
