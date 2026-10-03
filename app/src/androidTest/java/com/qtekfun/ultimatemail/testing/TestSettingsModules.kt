// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.data.settings.PreferenceOfflineDownloads
import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import com.qtekfun.ultimatemail.di.PickerModule
import com.qtekfun.ultimatemail.di.SearchModule
import com.qtekfun.ultimatemail.di.SettingsModule
import com.qtekfun.ultimatemail.domain.picker.RecentDestinations
import com.qtekfun.ultimatemail.domain.search.RecentSearches
import com.qtekfun.ultimatemail.domain.search.ServerSearch
import com.qtekfun.ultimatemail.domain.settings.OfflineDownloads
import com.qtekfun.ultimatemail.sync.engine.SearchOnServer
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/** The settings live in memory: a test never reads or changes the preferences of the device. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsModule::class])
abstract class TestSettingsModule {
    @Binds
    abstract fun preferenceStore(impl: InMemoryPreferenceStore): PreferenceStore

    @Binds
    abstract fun offlineDownloads(impl: PreferenceOfflineDownloads): OfflineDownloads
}

/** The recent destinations of the move picker, in memory. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PickerModule::class])
abstract class TestPickerModule {
    @Binds
    abstract fun recentDestinations(impl: InMemoryRecentDestinations): RecentDestinations
}

/** The search on the server (against the fake world) and the recent searches, in memory. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SearchModule::class])
abstract class TestSearchModule {
    @Binds
    abstract fun serverSearch(impl: SearchOnServer): ServerSearch

    @Binds
    abstract fun recentSearches(impl: InMemoryRecentSearches): RecentSearches
}
