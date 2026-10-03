// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.data.auth.AndroidKeystoreCipher
import com.qtekfun.ultimatemail.data.auth.CredentialStore
import com.qtekfun.ultimatemail.data.auth.SecretCipher
import com.qtekfun.ultimatemail.data.auth.UnavailableConnectionTester
import com.qtekfun.ultimatemail.data.auth.UnavailableOAuthTokenSource
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.account.OAuthTokenSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    /** Credentials are encrypted with a key that lives in Android Keystore. */
    @Binds
    abstract fun secretCipher(cipher: AndroidKeystoreCipher): SecretCipher

    /** Placeholder until T02 (AppAuth) provides the real OAuth token source. */
    @Binds
    abstract fun oauthTokenSource(source: UnavailableOAuthTokenSource): OAuthTokenSource

    /** Placeholder until the mail client (T07) can test connections. */
    @Binds
    abstract fun connectionTester(tester: UnavailableConnectionTester): AccountConnectionTester

    companion object {
        private const val CREDENTIALS_DIR = "credentials"

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
