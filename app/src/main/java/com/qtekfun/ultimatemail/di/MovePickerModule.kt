// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.ui.inbox.MovePickerLauncher
import com.qtekfun.ultimatemail.ui.inbox.SnackbarMovePickerLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** TODO(T17): bind the real move/label picker here; until then "Move" says "coming soon". */
@Module
@InstallIn(SingletonComponent::class)
abstract class MovePickerModule {
    @Binds
    abstract fun movePickerLauncher(impl: SnackbarMovePickerLauncher): MovePickerLauncher
}
