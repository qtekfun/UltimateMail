// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import javax.inject.Qualifier

/** The version name of the app (e.g. `0.1.0`), as a plain string. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppVersionName
