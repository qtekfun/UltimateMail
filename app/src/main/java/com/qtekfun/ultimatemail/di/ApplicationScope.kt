// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import javax.inject.Qualifier

/**
 * A scope that lives as long as the process, for work that must finish even if the screen that
 * started it is gone (the end of the undo window of a send).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
