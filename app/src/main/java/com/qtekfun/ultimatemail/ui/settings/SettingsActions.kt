// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import com.qtekfun.ultimatemail.data.settings.PreviewLines
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.ThemeMode

/** What the settings screen can do. */
data class SettingsActions(
    val onBack: () -> Unit,
    val onThemeChange: (ThemeMode) -> Unit,
    val onDensityChange: (DisplayDensity) -> Unit,
    val onPreviewLinesChange: (PreviewLines) -> Unit,
    val onShowAvatarsChange: (Boolean) -> Unit,
    val onDynamicColorChange: (Boolean) -> Unit,
    val onAmoledChange: (Boolean) -> Unit,
    val onSwipeRightChange: (SwipeAction) -> Unit,
    val onSwipeLeftChange: (SwipeAction) -> Unit,
    val onRemoteContentChange: (RemoteContentPolicy) -> Unit,
    val onOpenAccount: (Long) -> Unit,
    val onAddAccount: () -> Unit,
    val onExportAccounts: () -> Unit,
    val onImportAccounts: () -> Unit
)
