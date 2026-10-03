// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

/** The view models of the compose feature, created by the activity and handed to `AppRoot`. */
class ComposeScreens(
    val entry: ComposeEntryViewModel,
    val composer: ComposerViewModel,
    val drafts: DraftsViewModel,
    val outbox: OutboxViewModel
)
