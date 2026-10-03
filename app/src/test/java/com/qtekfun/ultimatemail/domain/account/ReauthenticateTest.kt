// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.account

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.sync.engine.AccountSyncState
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeVault : CredentialVault {
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

private class FakeTester : AccountConnectionTester {
    var result: ConnectionTestResult = ConnectionTestResult.Success
    val tested = mutableListOf<AccountInput>()

    override suspend fun test(input: AccountInput): ConnectionTestResult {
        tested += input
        return result
    }
}

private class RecordingScheduler : SyncScheduler {
    val requests = mutableListOf<Pair<Long?, Boolean>>()

    override fun startPeriodic() = Unit

    override fun requestSync(accountId: Long?, userInitiated: Boolean) {
        requests += accountId to userInitiated
    }

    override fun stop() = Unit
}

class ReauthenticateTest {
    private val db = inMemoryDatabase()
    private val vault = FakeVault()
    private val tester = FakeTester()
    private val status = SyncStatusStore()
    private val scheduler = RecordingScheduler()
    private val reauth = Reauthenticate(
        db.accountDao(),
        vault,
        tester,
        status,
        scheduler,
        Dispatchers.Unconfined
    )
    private val tokens =
        OAuthTokens("new-access", "new-refresh", Instant.ofEpochSecond(1_800_000_000))

    @AfterEach
    fun close() = db.close()

    private suspend fun addAccount(
        authType: AuthType = AuthType.PASSWORD,
        email: String = "ana@example.test"
    ): Long {
        val id = db.accountDao().insert(account(email).copy(authType = authType))
        vault.saved[id] = AccountCredentials(password = "old-password")
        status.set(id, AccountSyncState.ReauthenticationNeeded)
        return id
    }

    @Test
    fun `a good password replaces the old one, clears the state and asks for a sync`() = runTest {
        val id = addAccount()

        val result = reauth.withPassword(id, "new-password")

        assertEquals(ReauthResult.Success, result)
        assertEquals(AccountCredentials(password = "new-password"), vault.saved[id])
        assertEquals(AccountSyncState.Idle(), status.get(id))
        assertEquals(listOf<Pair<Long?, Boolean>>(id to true), scheduler.requests)
    }

    @Test
    fun `the login is tested with the stored servers and username`() = runTest {
        val id = addAccount()
        db.accountDao().update(db.accountDao().get(id)!!.copy(username = "ana.login"))

        reauth.withPassword(id, "new-password")

        val input = tester.tested.single()
        assertEquals("ana.login", input.username)
        assertEquals("imap.example.test", input.imap.host)
        assertEquals(993, input.imap.port)
        assertEquals("new-password", input.credentials.password)
    }

    @Test
    fun `a wrong password keeps the old credentials and the waiting state`() = runTest {
        val id = addAccount()
        tester.result = ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED)

        val result = reauth.withPassword(id, "wrong")

        assertEquals(ReauthResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED), result)
        assertEquals("old-password", vault.saved[id]?.password)
        assertEquals(AccountSyncState.ReauthenticationNeeded, status.get(id))
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `every connection failure is passed on as it is`() = runTest {
        val id = addAccount()

        ConnectionFailure.entries.forEach { reason ->
            tester.result = ConnectionTestResult.Failure(reason)
            assertEquals(ReauthResult.Failure(reason), reauth.withPassword(id, "pw"), "$reason")
        }
        assertEquals("old-password", vault.saved[id]?.password)
    }

    @Test
    fun `without a connection tester nothing is verified or stored`() = runTest {
        val id = addAccount()
        tester.result = ConnectionTestResult.NotAvailable

        assertEquals(ReauthResult.TestUnavailable, reauth.withPassword(id, "pw"))
        assertEquals("old-password", vault.saved[id]?.password)
        assertEquals(AccountSyncState.ReauthenticationNeeded, status.get(id))
    }

    @Test
    fun `a vault that fails keeps the old credentials and the waiting state`() = runTest {
        val id = addAccount()
        vault.failure = IOException("disk full")

        assertEquals(ReauthResult.StorageFailed, reauth.withPassword(id, "new"))

        assertEquals("old-password", vault.saved[id]?.password)
        assertEquals(AccountSyncState.ReauthenticationNeeded, status.get(id))
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `cancelling while saving is not turned into a failure`() = runTest {
        val id = addAccount()
        vault.failure = CancellationException("cancelled")

        var thrown: CancellationException? = null
        try {
            reauth.withPassword(id, "new")
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull(thrown)
        assertEquals(AccountSyncState.ReauthenticationNeeded, status.get(id))
    }

    @Test
    fun `an empty password is invalid and nothing is tried`() = runTest {
        val id = addAccount()

        assertEquals(ReauthResult.Invalid, reauth.withPassword(id, ""))
        assertTrue(tester.tested.isEmpty())
    }

    @Test
    fun `an account that does not exist is refused, whatever the method`() = runTest {
        assertEquals(ReauthResult.NoAccount, reauth.withPassword(99, "pw"))
        assertEquals(ReauthResult.NoAccount, reauth.withOAuth(99, "ana@example.test", tokens))
        assertNull(reauth.target(99))
        assertTrue(vault.saved.isEmpty())
        assertTrue(tester.tested.isEmpty())
    }

    @Test
    fun `a password cannot be put on an OAuth account and tokens not on a password one`() =
        runTest {
            val oauthId = addAccount(AuthType.OAUTH_GOOGLE, "g@example.test")
            val passwordId = addAccount(AuthType.PASSWORD, "p@example.test")

            assertEquals(ReauthResult.Invalid, reauth.withPassword(oauthId, "pw"))
            assertEquals(
                ReauthResult.Invalid,
                reauth.withOAuth(passwordId, "p@example.test", tokens)
            )
            assertEquals("old-password", vault.saved[oauthId]?.password)
            assertEquals("old-password", vault.saved[passwordId]?.password)
        }

    @Test
    fun `OAuth tokens for the same address, in any case, replace the credentials`() = runTest {
        val id = addAccount(AuthType.OAUTH_GOOGLE)

        val result = reauth.withOAuth(id, " ANA@Example.test ", tokens)

        assertEquals(ReauthResult.Success, result)
        assertEquals(AccountCredentials(oauth = tokens), vault.saved[id])
        assertEquals(AccountSyncState.Idle(), status.get(id))
        assertEquals(listOf<Pair<Long?, Boolean>>(id to true), scheduler.requests)
        assertEquals("new-access", tester.tested.single().credentials.oauth?.accessToken)
    }

    @Test
    fun `tokens of another address are refused before anything is tested or stored`() = runTest {
        val id = addAccount(AuthType.OAUTH_GOOGLE)

        val result = reauth.withOAuth(id, "someone.else@example.test", tokens)

        assertEquals(ReauthResult.AddressMismatch, result)
        assertTrue(tester.tested.isEmpty())
        assertEquals("old-password", vault.saved[id]?.password)
        assertNull(vault.saved[id]?.oauth)
        assertEquals(AccountSyncState.ReauthenticationNeeded, status.get(id))
    }

    @Test
    fun `rejected OAuth tokens keep the old credentials`() = runTest {
        val id = addAccount(AuthType.OAUTH_MICROSOFT)
        tester.result = ConnectionTestResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED)

        val result = reauth.withOAuth(id, "ana@example.test", tokens)

        assertEquals(ReauthResult.Failure(ConnectionFailure.AUTHENTICATION_FAILED), result)
        assertNull(vault.saved[id]?.oauth)
    }

    @Test
    fun `signing in again touches nothing but the credentials`() = runTest {
        val id = addAccount()
        db.folderDao().insertNew(listOf(folder(id)))
        db.messageDao().insertNew(listOf(message(id, uid = 1), message(id, uid = 2)))
        val accountBefore = db.accountDao().get(id)

        reauth.withPassword(id, "new-password")

        assertEquals(accountBefore, db.accountDao().get(id))
        assertEquals(listOf("INBOX"), db.folderDao().observeAll(id).first().map { it.path })
        assertEquals(setOf(1L, 2L), db.messageDao().serverUids(id, "INBOX").toSet())
    }

    @Test
    fun `the target describes the account without any secret`() = runTest {
        val id = addAccount(AuthType.OAUTH_GOOGLE)

        assertEquals(
            ReauthTarget(id, "ana@example.test", AuthType.OAUTH_GOOGLE, "imap.example.test"),
            reauth.target(id)
        )
    }

    @Test
    fun `clearing leaves other states alone`() {
        status.set(1, AccountSyncState.Syncing)
        status.set(2, AccountSyncState.Error(SyncProblem.NETWORK))

        status.clearReauthentication(1)
        status.clearReauthentication(2)

        assertEquals(AccountSyncState.Syncing, status.get(1))
        assertTrue(status.get(2) is AccountSyncState.Error)
    }
}
