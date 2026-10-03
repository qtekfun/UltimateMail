// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

/** What the export screen can do. */
data class ExportActions(
    val onBack: () -> Unit,
    val onPassphraseChange: (CharArray) -> Unit,
    val onIncludeCredentialsChange: (Boolean) -> Unit,
    val onSubmit: (CharArray, CharArray) -> Unit,
    val onLocationChosen: (String?) -> Unit,
    val onReset: () -> Unit
)
