// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountInputError.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal fun accountInput(
    email: String = "ana@example.test",
    authType: AuthType = AuthType.PASSWORD,
    credentials: AccountCredentials = AccountCredentials(password = "app-password"),
    imap: ServerEndpoint = ServerEndpoint("imap.example.test", 993, ConnectionSecurity.TLS),
    smtp: ServerEndpoint = ServerEndpoint("smtp.example.test", 587, ConnectionSecurity.STARTTLS),
    username: String = email,
    displayName: String = "Ana"
) = AccountInput(email, displayName, username, authType, imap, smtp, credentials)

class AccountValidatorTest {
    private val validator = AccountValidator()

    @Test
    fun `complete input is valid`() {
        assertEquals(emptyList<AccountInputError>(), validator.validate(accountInput()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "ana", "ana@", "@example.test", "ana@example", "a na@example.test",
            "a@@example.test", "a@-.", "ana@exa mple.test"
        ]
    )
    fun `malformed e-mail addresses are rejected`(email: String) {
        assertEquals(
            listOf(AccountInputError.InvalidEmail),
            validator.validate(accountInput(email = email, username = "ana"))
        )
    }

    @Test
    fun `a blank username is rejected`() {
        assertEquals(
            listOf(AccountInputError.InvalidUsername),
            validator.validate(accountInput(username = "  "))
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "imap example.test", "imap.example.test/path",
            "https://imap.example.test", "imap.example.test:993", "."
        ]
    )
    fun `bad hosts are reported for the server they belong to`(host: String) {
        val bad = ServerEndpoint(host, 993, ConnectionSecurity.TLS)

        assertEquals(
            listOf(AccountInputError.InvalidHost(Server.IMAP)),
            validator.validate(accountInput(imap = bad))
        )
        assertEquals(
            listOf(AccountInputError.InvalidHost(Server.SMTP)),
            validator.validate(accountInput(smtp = bad))
        )
    }

    @Test
    fun `ip addresses and single-label hosts are valid hosts`() {
        val local = ServerEndpoint("192.168.1.10", 143, ConnectionSecurity.STARTTLS)
        val single = ServerEndpoint("mailhost", 25, ConnectionSecurity.STARTTLS)

        assertEquals(
            emptyList<AccountInputError>(),
            validator.validate(accountInput(imap = local, smtp = single))
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 65536, 99999])
    fun `ports outside 1 to 65535 are rejected`(port: Int) {
        val bad = ServerEndpoint("imap.example.test", port, ConnectionSecurity.TLS)

        assertEquals(
            listOf(AccountInputError.InvalidPort(Server.IMAP)),
            validator.validate(accountInput(imap = bad))
        )
        assertEquals(
            listOf(AccountInputError.InvalidPort(Server.SMTP)),
            validator.validate(accountInput(smtp = bad))
        )
    }

    @Test
    fun `the port limits themselves are accepted`() {
        val low = ServerEndpoint("imap.example.test", 1, ConnectionSecurity.TLS)
        val high = ServerEndpoint("smtp.example.test", 65535, ConnectionSecurity.TLS)

        assertEquals(
            emptyList<AccountInputError>(),
            validator.validate(accountInput(imap = low, smtp = high))
        )
    }

    @Test
    fun `all problems are reported together`() {
        val bad = ServerEndpoint("", 0, ConnectionSecurity.TLS)

        val errors = validator.validate(
            accountInput(email = "x", username = "", imap = bad, credentials = AccountCredentials())
        )

        assertEquals(
            listOf(
                AccountInputError.InvalidEmail,
                AccountInputError.InvalidUsername,
                AccountInputError.InvalidHost(Server.IMAP),
                AccountInputError.InvalidPort(Server.IMAP),
                AccountInputError.MissingCredentials
            ),
            errors
        )
    }

    @Test
    fun `a password account needs a password`() {
        val none = accountInput(credentials = AccountCredentials(password = ""))
        val onlyTokens =
            accountInput(credentials = AccountCredentials(oauth = OAuthTokens("a", "r", null)))

        assertEquals(listOf(AccountInputError.MissingCredentials), validator.validate(none))
        assertEquals(listOf(AccountInputError.MissingCredentials), validator.validate(onlyTokens))
    }

    @Test
    fun `an OAuth account needs tokens, not a password`() {
        val tokens = AccountCredentials(oauth = OAuthTokens("a", "r", null))
        val password = AccountCredentials(password = "pw")

        for (type in listOf(AuthType.OAUTH_GOOGLE, AuthType.OAUTH_MICROSOFT)) {
            assertEquals(
                emptyList<AccountInputError>(),
                validator.validate(accountInput(authType = type, credentials = tokens))
            )
            assertEquals(
                listOf(AccountInputError.MissingCredentials),
                validator.validate(accountInput(authType = type, credentials = password))
            )
        }
    }

    @Test
    fun `domainOf lower-cases the domain and ignores surrounding spaces`() {
        assertEquals("example.test", AccountValidator.domainOf(" Ana@Example.TEST "))
        assertNull(AccountValidator.domainOf("nope"))
    }
}
