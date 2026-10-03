// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Gives the dialog its view model factory without needing a Hilt view model in the caller. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MoveLabelPickerEntryPoint {
    fun factory(): MoveLabelPickerViewModel.Factory
}
