// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.BuildConfig
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.di.AuthModule
import com.qtekfun.ultimatemail.di.MailModule
import com.qtekfun.ultimatemail.di.SyncModule
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds
import com.qtekfun.ultimatemail.domain.oauth.OAuthConfigs
import com.qtekfun.ultimatemail.sync.engine.LastSyncLog
import com.qtekfun.ultimatemail.sync.engine.MailOperationExecutor
import com.qtekfun.ultimatemail.sync.engine.SyncDepthLog
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatus
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import com.qtekfun.ultimatemail.sync.queue.HeldOperations
import com.qtekfun.ultimatemail.sync.queue.OperationExecutor
import com.qtekfun.ultimatemail.sync.queue.OperationQueue
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** IMAP and SMTP are the fakes of [FakeMailWorld]: no socket is ever opened. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MailModule::class])
abstract class TestMailModule {
    @Binds
    abstract fun connector(impl: FakeMailConnector): MailConnector

    @Binds
    abstract fun sender(impl: FakeMailSender): MailSender
}

/**
 * Credentials in memory, the connection test against the fake world, OAuth that never leaves
 * the process. The keystore cipher is not needed: nothing is written to disk.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AuthModule::class])
abstract class TestAuthModule {
    @Binds
    abstract fun connectionTester(impl: FakeConnectionTester): AccountConnectionTester

    @Binds
    abstract fun vault(impl: InMemoryVault): CredentialVault

    @Binds
    abstract fun clientIds(impl: InMemoryClientIds): OAuthClientIds

    companion object {
        @Provides
        @Singleton
        fun oauthConfigs(clientIds: OAuthClientIds): OAuthConfigs =
            OAuthConfigs(clientIds, BuildConfig.APPLICATION_ID, "")

        /** No provider is reachable from a test: a refresh is "not available". */
        @Provides
        fun oauthTokenSource(): OAuthTokenSource = object : OAuthTokenSource {
            override suspend fun refresh(
                authType: AuthType,
                refreshToken: String
            ): OAuthRefreshResult = OAuthRefreshResult.Unavailable
        }
    }
}

/** The sync engine as it is, with a scheduler that does not use WorkManager. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SyncModule::class])
abstract class TestSyncModule {
    @Binds
    abstract fun operationExecutor(impl: MailOperationExecutor): OperationExecutor

    @Binds
    abstract fun syncStatus(impl: SyncStatusStore): SyncStatus

    @Binds
    abstract fun syncScheduler(impl: TestSyncScheduler): SyncScheduler

    @Binds
    abstract fun heldOperations(impl: OperationQueue): HeldOperations

    companion object {
        /** Nothing outlives a test: no remembered sync time and no remembered sync depth. */
        @Provides
        fun lastSyncLog(): LastSyncLog = LastSyncLog.None

        @Provides
        fun syncDepthLog(): SyncDepthLog = SyncDepthLog.None
    }
}
