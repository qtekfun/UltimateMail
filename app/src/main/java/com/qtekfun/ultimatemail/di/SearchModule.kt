// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.data.search.SharedPreferencesRecentSearches
import com.qtekfun.ultimatemail.domain.search.RecentSearches
import com.qtekfun.ultimatemail.domain.search.ServerSearch
import com.qtekfun.ultimatemail.sync.engine.SearchOnServer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The search of the search screen: the search on the server and the recent searches. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds
    abstract fun serverSearch(impl: SearchOnServer): ServerSearch

    companion object {
        private const val PREFERENCES = "recent_searches"

        @Provides
        @Singleton
        fun recentSearches(@ApplicationContext context: Context): RecentSearches =
            SharedPreferencesRecentSearches(
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            )
    }
}
