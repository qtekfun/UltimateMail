// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.domain.conversation.ComposeLauncher
import com.qtekfun.ultimatemail.ui.compose.QueuedComposeLauncher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The composer's entry point for the reading screen, and its process-wide scope. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeModule {
    @Binds
    abstract fun composeLauncher(impl: QueuedComposeLauncher): ComposeLauncher

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
