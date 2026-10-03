// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.data.settings.PreferenceOfflineDownloads
import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import com.qtekfun.ultimatemail.data.settings.SharedPreferencesStore
import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun preferenceStore(store: SharedPreferencesStore): PreferenceStore

    @Binds
    abstract fun offlineDownloads(impl: PreferenceOfflineDownloads): OfflineDownloads
}
