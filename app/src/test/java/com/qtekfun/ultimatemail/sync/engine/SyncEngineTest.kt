// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import app.cash.turbine.test
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.account.ReauthResult
import com.qtekfun.ultimatemail.domain.account.Reauthenticate
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(authType: AuthType = AuthType.PASSWORD): EngineHarness {
        val h = EngineHarness(this, authType)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.server.deliver("INBOX")
        h.addAccount()
        return h
    }

    @Test
    fun `status goes from idle to syncing to idle with the time of the sync`() = runTest {
        val h = start()

        h.status.observe(h.accountId).test {
            assertEquals(AccountSyncState.Idle(), awaitItem())
            val sync = launch { h.engine.sync(h.accountId) }
            assertEquals(AccountSyncState.Syncing, awaitItem())
            assertEquals(AccountSyncState.SyncingFolders(0, 1), awaitItem())
            assertEquals(AccountSyncState.Idle(h.clock.now), awaitItem())
            sync.join()
        }
        assertEquals(
            mapOf<Long, AccountSyncState>(h.accountId to AccountSyncState.Idle(h.clock.now)),
            h.status.states.value
        )
    }

    @Test
    fun `a failed sync shows the problem and the next good one clears it`() = runTest {
        val h = start()
        h.connector.failure = MailResult.NetworkUnavailable

        assertEquals(AccountSyncResult.Failed(SyncProblem.NETWORK), h.engine.sync(h.accountId))
        assertEquals(AccountSyncState.Error(SyncProblem.NETWORK), h.status.get(h.accountId))

        h.connector.failure = null
        h.engine.sync(h.accountId)
        assertEquals(AccountSyncState.Idle(h.clock.now), h.status.get(h.accountId))
    }

    @Test
    fun `a rejected password pauses the account until the user retries`() = runTest {
        val h = start()
        h.engine.sync(h.accountId)
        h.connector.failure = MailResult.AuthenticationFailed

        val first = h.engine.sync(h.accountId)
        val connectsAfterFirst = h.connector.connects.size
        val background = h.engine.sync(h.accountId)

        assertEquals(AccountSyncResult.ReauthenticationNeeded, first)
        assertEquals(AccountSyncResult.ReauthenticationNeeded, background)
        assertEquals(
            connectsAfterFirst,
            h.connector.connects.size,
            "no more login attempts in the background"
        )
        assertEquals(AccountSyncState.ReauthenticationNeeded, h.status.get(h.accountId))
        assertEquals(1, h.messages.serverUids(h.accountId, "INBOX").size, "local data stays")

        h.connector.failure = null
        val retried = h.engine.sync(h.accountId, userInitiated = true)

        assertTrue(retried is AccountSyncResult.Synced)
        assertEquals(AccountSyncState.Idle(h.clock.now), h.status.get(h.accountId))
    }

    @Test
    fun `an account without stored credentials needs a new sign in`() = runTest {
        val h = start()
        h.vault.saved.clear()

        assertEquals(AccountSyncResult.ReauthenticationNeeded, h.engine.sync(h.accountId))
    }

    @Test
    fun `a second sync of an account that is already syncing leaves the work to the first`() =
        runTest {
            val h = start()
            val gate = CompletableDeferred<Unit>()
            h.connector.gate = gate
            val first = launch { h.engine.sync(h.accountId) }
            testScheduler.advanceUntilIdle()

            val second = h.engine.sync(h.accountId)
            gate.complete(Unit)
            first.join()

            assertEquals(AccountSyncResult.AlreadyRunning, second)
        }

    @Test
    fun `syncing everything reports each account and retries network problems`() = runTest {
        val h = start()
        val second = h.db.accountDao().insert(
            com.qtekfun.ultimatemail.data.local.account("other@example.test")
        )
        h.vault.save(second, AccountCredentials(password = "pw"))
        h.connector.failure = MailResult.Timeout

        val report = h.engine.syncAll()

        assertEquals(
            mapOf(
                h.accountId to AccountSyncResult.Failed(SyncProblem.TIMEOUT),
                second to AccountSyncResult.Failed(SyncProblem.TIMEOUT)
            ),
            report.results
        )
        assertTrue(report.shouldRetry)
        h.connector.failure = null
        assertTrue(!h.engine.syncAll().shouldRetry)
    }

    @Test
    fun `a certificate problem is reported and not retried by the scheduler`() = runTest {
        val h = start()
        h.connector.failure = MailResult.CertificateRejected

        val report = h.engine.syncAll()

        assertEquals(
            AccountSyncResult.Failed(SyncProblem.CERTIFICATE),
            report.results.getValue(h.accountId)
        )
        assertTrue(!report.shouldRetry)
    }

    @Test
    fun `syncing an account that does not exist reports it and leaves no status`() = runTest {
        val h = start()

        val result = h.engine.sync(999)

        assertEquals(AccountSyncResult.NoAccount, result)
        assertTrue(999L !in h.status.states.value)
    }

    @Test
    fun `cancelling a sync puts the previous status back`() = runTest {
        val h = start()
        h.server.failure = { name ->
            if (name == "listFolders") throw kotlinx.coroutines.CancellationException("stop")
            null
        }

        val job = launch { h.engine.sync(h.accountId) }
        job.join()

        assertEquals(AccountSyncState.Idle(), h.status.get(h.accountId))
    }

    @Test
    fun `every failure class has a problem for the UI`() {
        val cases = mapOf(
            MailResult.NetworkUnavailable to SyncProblem.NETWORK,
            MailResult.Timeout to SyncProblem.TIMEOUT,
            MailResult.CertificateRejected to SyncProblem.CERTIFICATE,
            MailResult.NotFound to SyncProblem.SERVER,
            MailResult.Unsupported("x") to SyncProblem.SERVER,
            MailResult.Protocol to SyncProblem.PROTOCOL,
            MailResult.Unknown to SyncProblem.UNKNOWN,
            MailResult.AuthenticationFailed to SyncProblem.UNKNOWN
        )
        cases.forEach { (failure, problem) ->
            assertEquals(problem, failure.toProblem(), "for $failure")
        }
    }

    // --- credentials ---

    @Test
    fun `an OAuth account logs in with its access token`() = runTest {
        val h = start(AuthType.OAUTH_GOOGLE)
        h.vault.save(
            h.accountId,
            AccountCredentials(
                oauth = OAuthTokens("tok", "ref", Instant.ofEpochSecond(2_000_000_000))
            )
        )

        h.engine.sync(h.accountId)

        val login = h.connector.connects.single() as MailCredentials.OAuthBearer
        assertEquals("tok", login.accessToken)
        assertTrue(h.oauth.refreshes.isEmpty())
    }

    @Test
    fun `an expired token is refreshed and the new tokens are saved`() = runTest {
        val h = start(AuthType.OAUTH_GOOGLE)
        h.vault.save(
            h.accountId,
            AccountCredentials(oauth = OAuthTokens("old", "ref", Instant.EPOCH))
        )
        h.oauth.result =
            OAuthRefreshResult.Refreshed(
                OAuthTokens("new", null, Instant.ofEpochSecond(2_000_000_000))
            )

        h.engine.sync(h.accountId)

        assertEquals(
            "new",
            (h.connector.connects.single() as MailCredentials.OAuthBearer).accessToken
        )
        val saved = h.vault.saved.getValue(h.accountId).oauth!!
        assertEquals("new", saved.accessToken)
        assertEquals(
            "ref",
            saved.refreshToken,
            "the refresh token is kept when the provider sends none"
        )
    }

    @Test
    fun `a revoked OAuth grant asks for a new sign in`() = runTest {
        val h = start(AuthType.OAUTH_GOOGLE)
        h.vault.save(
            h.accountId,
            AccountCredentials(oauth = OAuthTokens("old", "ref", Instant.EPOCH))
        )
        h.oauth.result = OAuthRefreshResult.Revoked

        assertEquals(AccountSyncResult.ReauthenticationNeeded, h.engine.sync(h.accountId))
    }

    @Test
    fun `OAuth trouble that may pass is a network problem`() = runTest {
        val h = start(AuthType.OAUTH_MICROSOFT)
        h.vault.save(
            h.accountId,
            AccountCredentials(oauth = OAuthTokens("old", "ref", Instant.EPOCH))
        )

        h.oauth.result = OAuthRefreshResult.TemporaryFailure
        assertEquals(AccountSyncResult.Failed(SyncProblem.NETWORK), h.engine.sync(h.accountId))
        h.oauth.result = OAuthRefreshResult.Unavailable
        assertEquals(AccountSyncResult.Failed(SyncProblem.NETWORK), h.engine.sync(h.accountId))
    }

    @Test
    fun `an OAuth account without a refresh token for an expired access token signs in again`() =
        runTest {
            val h = start(AuthType.OAUTH_GOOGLE)
            h.vault.save(
                h.accountId,
                AccountCredentials(oauth = OAuthTokens("old", null, Instant.EPOCH))
            )

            assertEquals(AccountSyncResult.ReauthenticationNeeded, h.engine.sync(h.accountId))
        }

    @Test
    fun `credentials of the wrong kind need a new sign in`() = runTest {
        val oauth = start(AuthType.OAUTH_GOOGLE)
        oauth.vault.save(oauth.accountId, AccountCredentials(password = "pw"))
        assertEquals(
            AccountSyncResult.ReauthenticationNeeded,
            oauth.engine.sync(oauth.accountId)
        )

        val password = EngineHarness(this).also { harness = it }
        password.addAccount()
        password.vault.save(
            password.accountId,
            AccountCredentials(oauth = OAuthTokens("t", "r", null))
        )
        assertEquals(
            AccountSyncResult.ReauthenticationNeeded,
            password.engine.sync(password.accountId)
        )
        oauth.close()
    }

    @Test
    fun `a token the server rejects is refreshed once before asking the user`() = runTest {
        val h = start(AuthType.OAUTH_GOOGLE)
        h.vault.save(
            h.accountId,
            AccountCredentials(
                oauth = OAuthTokens("stale", "ref", Instant.ofEpochSecond(2_000_000_000))
            )
        )
        h.oauth.result =
            OAuthRefreshResult.Refreshed(
                OAuthTokens("fresh", "ref", Instant.ofEpochSecond(2_000_000_000))
            )
        h.connector.failure = MailResult.AuthenticationFailed

        h.engine.sync(h.accountId)
        val attempts = h.connector.connects.size

        assertEquals(2, attempts, "stale token, then fresh token")
        assertEquals(
            listOf("stale", "fresh"),
            h.connector.connects.map {
                (it as MailCredentials.OAuthBearer).accessToken
            }
        )
    }

    @Test
    fun `notices wait for the UI and arrive once`() = runTest {
        val h = start()
        val notice = SyncNotice.FolderReset(h.accountId, "INBOX")
        h.notices.publish(notice)

        h.notices.notices.test {
            assertEquals(notice, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signing in again lets the next background sync use the new password at once`() = runTest {
        val h = start()
        h.engine.sync(h.accountId)
        h.connector.failure = MailResult.AuthenticationFailed
        h.engine.sync(h.accountId)
        assertEquals(AccountSyncState.ReauthenticationNeeded, h.status.get(h.accountId))
        val requests = mutableListOf<Boolean>()
        val reauth = Reauthenticate(
            h.db.accountDao(),
            h.vault,
            object : AccountConnectionTester {
                override suspend fun test(input: AccountInput) = ConnectionTestResult.Success
            },
            h.status,
            object : SyncScheduler {
                override fun startPeriodic() = Unit

                override fun requestSync(accountId: Long?, userInitiated: Boolean) {
                    requests += userInitiated
                }

                override fun stop() = Unit
            },
            Dispatchers.Unconfined
        )

        h.connector.failure = null
        assertEquals(ReauthResult.Success, reauth.withPassword(h.accountId, "new-pw"))
        val background = h.engine.sync(h.accountId)

        assertTrue(background is AccountSyncResult.Synced, "no userInitiated flag needed")
        assertEquals(listOf(true), requests)
        assertEquals(
            MailCredentials.Password("ana@example.test", "new-pw"),
            h.connector.connects.last()
        )
        assertEquals(1, h.messages.serverUids(h.accountId, "INBOX").size, "local data stays")
    }
}
