// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.BuildConfig
import com.qtekfun.ultimatemail.data.auth.AndroidKeystoreCipher
import com.qtekfun.ultimatemail.data.auth.CredentialStore
import com.qtekfun.ultimatemail.data.auth.MailAccountConnectionTester
import com.qtekfun.ultimatemail.data.auth.SecretCipher
import com.qtekfun.ultimatemail.data.oauth.ClientIdPreferences
import com.qtekfun.ultimatemail.data.oauth.HttpOAuthTokenSource
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds
import com.qtekfun.ultimatemail.domain.oauth.OAuthConfigs
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    /** Credentials are encrypted with a key that lives in Android Keystore. */
    @Binds
    abstract fun secretCipher(cipher: AndroidKeystoreCipher): SecretCipher

    /** Opens a real IMAP session to test the settings of a new account. */
    @Binds
    abstract fun connectionTester(tester: MailAccountConnectionTester): AccountConnectionTester

    companion object {
        private const val CREDENTIALS_DIR = "credentials"

        /** The client IDs the user entered live in private preferences on the device. */
        @Provides
        @Singleton
        fun oauthClientIds(@ApplicationContext context: Context): OAuthClientIds =
            ClientIdPreferences(context)

        @Provides
        @Singleton
        fun oauthConfigs(clientIds: OAuthClientIds): OAuthConfigs =
            OAuthConfigs(clientIds, BuildConfig.APPLICATION_ID, BuildConfig.GOOGLE_CLIENT_ID)

        /** Refreshes access tokens against the token endpoint of Google or Microsoft. */
        @Provides
        @Singleton
        fun oauthTokenSource(
            configs: OAuthConfigs,
            @IoDispatcher io: CoroutineDispatcher,
            clock: Clock
        ): OAuthTokenSource = HttpOAuthTokenSource(configs::configFor, io, clock)

        /** Kept in noBackupFilesDir so encrypted secrets are never copied off the device. */
        @Provides
        @Singleton
        fun credentialVault(
            @ApplicationContext context: Context,
            cipher: SecretCipher,
            @IoDispatcher io: CoroutineDispatcher
        ): CredentialVault =
            CredentialStore(File(context.noBackupFilesDir, CREDENTIALS_DIR), cipher, io)
    }
}
