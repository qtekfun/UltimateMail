// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.data.picker.SharedPreferencesRecentDestinations
import com.qtekfun.ultimatemail.domain.picker.RecentDestinations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The store behind the "recent destinations" of the move / label picker. */
@Module
@InstallIn(SingletonComponent::class)
object PickerModule {
    private const val PREFERENCES = "recent_destinations"

    @Provides
    @Singleton
    fun recentDestinations(@ApplicationContext context: Context): RecentDestinations =
        SharedPreferencesRecentDestinations(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        )
}
