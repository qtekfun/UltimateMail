// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

class MemoryVault : CredentialVault {
    val saved = mutableMapOf<Long, AccountCredentials>()

    override suspend fun save(accountId: Long, credentials: AccountCredentials) {
        saved[accountId] = credentials
    }

    override suspend fun load(accountId: Long) = saved[accountId]

    override suspend fun delete(accountId: Long) {
        saved.remove(accountId)
    }
}

class FakeOAuth : OAuthTokenSource {
    var result: OAuthRefreshResult = OAuthRefreshResult.Revoked
    val refreshes = mutableListOf<String>()

    override suspend fun refresh(authType: AuthType, refreshToken: String): OAuthRefreshResult {
        refreshes += refreshToken
        return result
    }
}

class FakeConnector(private val server: FakeMailServer) : MailConnector {
    /** When set, every connect answers with it instead of a session. */
    var failure: MailResult.Failure? = null
    val connects = mutableListOf<MailCredentials>()
    val sessions = mutableListOf<FakeSession>()

    /** When set, connecting waits for it: holds a sync in the middle of its run. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun connect(
        server: MailServer,
        credentials: MailCredentials
    ): MailResult<MailSession> {
        connects += credentials
        gate?.await()
        failure?.let { return it }
        return MailResult.Success(FakeSession(this.server).also { sessions += it })
    }
}

class FakeSender : MailSender {
    val sent = mutableListOf<OutgoingMessage>()

    /** Called for every send; return a failure to refuse it, null to accept it. */
    var onSend: (OutgoingMessage) -> MailResult.Failure? = { null }

    override suspend fun send(
        server: MailServer,
        credentials: MailCredentials?,
        message: OutgoingMessage
    ): MailResult<String> {
        sent += message
        return onSend(message) ?: MailResult.Success(message.messageId.orEmpty())
    }
}

/** Everything the engine needs, wired to an in-memory database and the fake server. */
class EngineHarness(scope: TestScope, authType: AuthType = AuthType.PASSWORD) {
    val clock = MutableClock(Instant.ofEpochSecond(1_700_100_000))
    val db: UltimateMailDatabase = inMemoryDatabase()
    val server = FakeMailServer()
    val vault = MemoryVault()
    val oauth = FakeOAuth()
    val connector = FakeConnector(server)
    val sender = FakeSender()
    val status = SyncStatusStore()
    val notices = SyncNotices()
    val credentials = MailCredentialsProvider(vault, oauth, clock)
    val sessions = AccountSessions(db.accountDao(), credentials, connector, status)
    val marker = PendingSyncMarker(db.messageDao(), db.pendingOperationDao())
    val executor = MailOperationExecutor(
        db.accountDao(),
        db.folderDao(),
        db.messageDao(),
        sessions,
        credentials,
        sender,
        marker,
        status,
        notices
    )
    val queue = OperationQueue(
        db.pendingOperationDao(),
        executor,
        clock,
        StandardTestDispatcher(scope.testScheduler)
    )
    val accountSync = AccountSync(
        db.accountDao(),
        db.folderDao(),
        db.messageDao(),
        sessions,
        FolderCatalog(db.folderDao()),
        FolderPuller(
            db.messageDao(),
            db.folderDao(),
            PendingReconciler(db.messageDao(), db.pendingOperationDao(), notices),
            clock
        ),
        queue,
        clock
    )
    val engine = SyncEngine(db.accountDao(), accountSync, status, clock)
    var accountId = 0L
        private set

    val messages get() = db.messageDao()
    val folders get() = db.folderDao()
    val operations get() = db.pendingOperationDao()

    private val authTypeOf = authType

    suspend fun addAccount(entity: AccountEntity = account().copy(authType = authTypeOf)): Long {
        accountId = db.accountDao().insert(entity)
        vault.save(accountId, AccountCredentials(password = "pw"))
        return accountId
    }

    fun close() = db.close()
}
