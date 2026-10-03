// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import com.qtekfun.ultimatemail.domain.settings.OfflineWindow

/** What the settings screen of an account can do. */
data class AccountSettingsActions(
    val onBack: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onSignatureChange: (String) -> Unit,
    val onSignatureEnabledChange: (Boolean) -> Unit,
    val onBeforeQuoteChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onOfflineWindowChange: (OfflineWindow) -> Unit,
    val onDownloadForOfflineChange: (Boolean) -> Unit,
    val onFolderSyncChange: (path: String, enabled: Boolean) -> Unit,
    val onRequestRemoval: () -> Unit,
    val onDismissRemoval: () -> Unit,
    val onConfirmRemoval: () -> Unit
)
