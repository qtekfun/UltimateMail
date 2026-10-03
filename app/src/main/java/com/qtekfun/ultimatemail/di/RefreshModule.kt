// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * PLACEHOLDER binding of [RefreshTrigger]: the sync engine (T10) is not part of the app yet, so
 * pull-to-refresh does nothing but show its spinner. T10 replaces this provider with one that
 * starts a real sync; nothing else needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshModule {
    @Provides
    fun refreshTrigger(): RefreshTrigger = RefreshTrigger { }
}
