// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.auth.UnavailableOAuthTokenSource
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.AuthType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class MemoryVault : CredentialVault {
    val saved = mutableMapOf<Long, AccountCredentials>()
    var failure: Exception? = null

    override suspend fun save(accountId: Long, credentials: AccountCredentials) {
        failure?.let { throw it }
        saved[accountId] = credentials
    }

    override suspend fun load(accountId: Long) = saved[accountId]

    override suspend fun delete(accountId: Long) {
        saved.remove(accountId)
    }
}

class AccountSetupTest {
    private val db = inMemoryDatabase()
    private val vault = MemoryVault()
    private val tester = mockk<AccountConnectionTester>()
    private val setup = AccountSetup(
        db,
        vault,
        AccountValidator(),
        ServerAutodetector(),
        tester,
        Dispatchers.Unconfined
    )

    @AfterEach
    fun close() = db.close()

    @Test
    fun `creating an account stores the row and the credentials`() = runTest {
        val result = setup.create(accountInput(displayName = " "))

        val id = (result as CreateAccountResult.Created).accountId
        val stored = db.accountDao().get(id)!!
        assertEquals("ana@example.test", stored.email)
        assertEquals("ana", stored.displayName)
        assertEquals("imap.example.test", stored.imapHost)
        assertEquals(587, stored.smtpPort)
        assertEquals("app-password", vault.saved[id]?.password)
    }

    @Test
    fun `an explicit display name and trimmed e-mail are kept`() = runTest {
        val id = (
            setup.create(
                accountInput(email = " ana@example.test ", displayName = "Ana B")
            ) as CreateAccountResult.Created
            ).accountId

        val stored = db.accountDao().get(id)!!
        assertEquals("Ana B", stored.displayName)
        assertEquals("ana@example.test", stored.email)
    }

    @Test
    fun `OAuth accounts store their tokens`() = runTest {
        val tokens = AccountCredentials(oauth = OAuthTokens("a", "r", null))

        val id = (
            setup.create(
                accountInput(authType = AuthType.OAUTH_GOOGLE, credentials = tokens)
            ) as CreateAccountResult.Created
            ).accountId

        assertEquals(tokens, vault.saved[id])
        assertEquals(AuthType.OAUTH_GOOGLE, db.accountDao().get(id)!!.authType)
    }

    @Test
    fun `invalid input creates nothing`() = runTest {
        val result = setup.create(accountInput(email = "nope"))

        assertEquals(CreateAccountResult.Invalid(listOf(AccountInputError.InvalidEmail)), result)
        assertTrue(db.accountDao().observeAll().first().isEmpty())
        assertTrue(vault.saved.isEmpty())
    }

    @Test
    fun `the same address cannot be added twice, whatever its case`() = runTest {
        setup.create(accountInput())

        val result = setup.create(accountInput(email = "ANA@example.test"))

        assertEquals(
            CreateAccountResult.Invalid(listOf(AccountInputError.DuplicateAccount)),
            result
        )
        assertEquals(1, db.accountDao().observeAll().first().size)
    }

    @Test
    fun `when credentials cannot be stored no account row remains`() = runTest {
        vault.failure = IOException("disk full")

        val result = setup.create(accountInput())

        assertEquals(CreateAccountResult.StorageFailed, result)
        assertTrue(db.accountDao().observeAll().first().isEmpty())
        assertTrue(vault.saved.isEmpty())
    }

    @Test
    fun `cancellation while storing credentials also removes the row and is propagated`() =
        runTest {
            vault.failure = CancellationException("cancelled")

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { setup.create(accountInput()) }
            }

            assertTrue(db.accountDao().observeAll().first().isEmpty())
        }

    @Test
    fun `detection is delegated to the autodetector`() {
        assertEquals(AuthType.OAUTH_GOOGLE, setup.detectServers("a@gmail.com")?.authType)
        assertEquals(null, setup.detectServers("nope"))
    }

    @Test
    fun `validate reports the validator errors`() {
        assertEquals(
            listOf(AccountInputError.InvalidUsername),
            setup.validate(accountInput(username = ""))
        )
    }

    @Test
    fun `testing a valid input asks the connection tester`() = runTest {
        val input = accountInput()
        coEvery { tester.test(input) } returns
            ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED)

        val result = setup.testConnection(input)

        assertEquals(ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED), result)
    }

    @Test
    fun `invalid input is not sent to the connection tester`() = runTest {
        val result = setup.testConnection(accountInput(email = "nope"))

        assertEquals(ConnectionTestResult.Failure(ConnectionFailure.UNKNOWN), result)
        coVerify(exactly = 0) { tester.test(any()) }
    }

    @Test
    fun `the placeholder token source refreshes nothing`() = runTest {
        assertEquals(
            OAuthRefreshResult.Unavailable,
            UnavailableOAuthTokenSource().refresh(AuthType.OAUTH_GOOGLE, "r")
        )
        assertInstanceOf(OAuthRefreshResult::class.java, OAuthRefreshResult.Revoked)
    }
}
