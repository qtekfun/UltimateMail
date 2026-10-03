// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.data.mail.AngusMailConnector
import com.qtekfun.ultimatemail.data.mail.AngusMailSender
import com.qtekfun.ultimatemail.data.mail.GmailExtensions
import com.qtekfun.ultimatemail.data.mail.MailClientConfig
import com.qtekfun.ultimatemail.data.mail.ProviderExtensions
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailSender
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the mail gateway interfaces of the domain layer to their Angus Mail implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MailModule {
    @Binds
    abstract fun connector(impl: AngusMailConnector): MailConnector

    @Binds
    abstract fun sender(impl: AngusMailSender): MailSender

    companion object {
        /** System trust store, default timeouts. */
        @Provides
        fun config(): MailClientConfig = MailClientConfig()

        @Provides
        fun providerExtensions(): ProviderExtensions = GmailExtensions()
    }
}
