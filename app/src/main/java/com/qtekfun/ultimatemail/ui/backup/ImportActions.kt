// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.backup

/** What the import screen can do. */
data class ImportActions(
    val onBack: () -> Unit,
    val onFileChosen: (String?) -> Unit,
    val onOpen: (CharArray) -> Unit,
    val onToggle: (Int) -> Unit,
    val onImportSettingsChange: (Boolean) -> Unit,
    val onImport: () -> Unit,
    /** Opens the sign-in-again screen of an imported account that came without credentials. */
    val onSignIn: (Long) -> Unit = {}
)
