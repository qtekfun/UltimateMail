// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.settings.DisplayDensity
import com.qtekfun.ultimatemail.data.settings.PreviewLines
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.data.settings.SwipeAction
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.settings.AppLanguage
import com.qtekfun.ultimatemail.domain.settings.OfflineWindow

/** The text of each option shown in the settings screens. */
@StringRes
internal fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@StringRes
internal fun AppLanguage.label(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.SPANISH -> R.string.language_spanish
}

@StringRes
internal fun SwipeAction.label(): Int = when (this) {
    SwipeAction.ARCHIVE -> R.string.swipe_archive
    SwipeAction.DELETE -> R.string.swipe_delete
    SwipeAction.MOVE -> R.string.swipe_move
    SwipeAction.TOGGLE_READ -> R.string.swipe_toggle_read
    SwipeAction.TOGGLE_STAR -> R.string.swipe_toggle_star
    SwipeAction.NONE -> R.string.swipe_none
}

@StringRes
internal fun RemoteContentPolicy.label(): Int = when (this) {
    RemoteContentPolicy.NEVER -> R.string.remote_never
    RemoteContentPolicy.ASK -> R.string.remote_ask
}

@StringRes
internal fun OfflineWindow.label(): Int = when (this) {
    OfflineWindow.DAYS_30 -> R.string.offline_30_days
    OfflineWindow.DAYS_90 -> R.string.offline_90_days
    OfflineWindow.DAYS_180 -> R.string.offline_180_days
    OfflineWindow.YEAR -> R.string.offline_1_year
    OfflineWindow.ALL -> R.string.offline_all
}

internal fun DisplayDensity.label(): Int = when (this) {
    DisplayDensity.COMFORTABLE -> R.string.density_comfortable
    DisplayDensity.DEFAULT -> R.string.density_default
    DisplayDensity.COMPACT -> R.string.density_compact
}

/** The text of a preview option: "None" or "n lines". */
@Composable
internal fun PreviewLines.label(): String = if (this == PreviewLines.NONE) {
    stringResource(R.string.preview_none)
} else {
    pluralStringResource(R.plurals.preview_lines, count, count)
}
