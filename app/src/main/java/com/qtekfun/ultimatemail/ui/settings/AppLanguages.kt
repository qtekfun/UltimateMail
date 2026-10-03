// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.qtekfun.ultimatemail.domain.settings.AppLanguage

/**
 * The per-app language, kept by the system (LocaleManager, Android 13+); `generateLocaleConfig`
 * publishes the supported languages to it. Older versions follow the system language: the
 * AppCompat per-app locale API needs appcompat 1.6, and the project only has 1.3 through AppAuth.
 */
internal object AppLanguages {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun current(context: Context): AppLanguage = AppLanguage.fromTags(
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun set(context: Context, language: AppLanguage) {
        val tag = language.tag
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
