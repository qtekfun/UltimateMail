// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.settings

/** The languages the app can be set to; a null [tag] follows the system. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    SPANISH("es");

    companion object {
        /**
         * The language for the language tags the system reports as the app's locales (e.g.
         * "es-ES,en"): the first one decides; empty or unsupported means follow the system.
         */
        fun fromTags(tags: String): AppLanguage {
            val first = tags.substringBefore(',').trim().substringBefore('-')
            return entries.firstOrNull { it.tag != null && it.tag.equals(first, true) } ?: SYSTEM
        }
    }
}
