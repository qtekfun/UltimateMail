// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.domain.conversation.ComposeLauncher
import com.qtekfun.ultimatemail.domain.conversation.ComposeUnavailable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** TODO(T18): bind the real composer here; until then reply and forward say "coming soon". */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeModule {
    @Binds
    abstract fun composeLauncher(impl: ComposeUnavailable): ComposeLauncher
}
