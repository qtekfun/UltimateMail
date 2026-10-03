// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.ui.inbox.DialogMovePickerLauncher
import com.qtekfun.ultimatemail.ui.inbox.MovePickerLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the swipe "Move" and the selection bar to the real move/label picker (T17). */
@Module
@InstallIn(SingletonComponent::class)
abstract class MovePickerModule {
    @Binds
    abstract fun movePickerLauncher(impl: DialogMovePickerLauncher): MovePickerLauncher
}
