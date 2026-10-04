// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.data.settings.AppSettings
import com.qtekfun.ultimatemail.data.settings.PreviewLines

/** The accent blue of the unread dot when dynamic colors are off, in the light theme. */
internal val IosBlueLight = Color(0xFF007AFF)

/** The accent blue of the unread dot when dynamic colors are off, in the dark theme. */
internal val IosBlueDark = Color(0xFF0A84FF)

/** Width of the slot at the start of a row that holds the unread dot or the selection mark. */
val RowLeadingSlot: Dp = 28.dp

/** Space between the screen edge and the leading slot of a row. */
val RowStartPadding: Dp = 4.dp

/** The avatar (40dp) plus the gap after it, when avatars are on. */
val RowAvatarSlot: Dp = 52.dp

/**
 * What the settings change in a conversation row: the lines of preview, whether the avatar is
 * shown, and the color of the unread dot. Provided once by [UltimateMailTheme] through
 * [LocalRowAppearance], so the rows read it without every list passing it down.
 */
data class RowAppearance(val previewLines: Int, val showAvatars: Boolean, val unreadColor: Color) {
    /** Where the text of a row starts, counted from the start of the row. */
    val textIndent: Dp
        get() = RowStartPadding + RowLeadingSlot + if (showAvatars) RowAvatarSlot else 0.dp
}

/**
 * The [RowAppearance] for [settings]. The dot takes the [primary] color of the theme when dynamic
 * colors are really in use ([dynamicInUse]), the iOS blue otherwise.
 */
internal fun rowAppearanceFor(
    settings: AppSettings,
    dark: Boolean,
    dynamicInUse: Boolean,
    primary: Color
): RowAppearance = RowAppearance(
    previewLines = settings.previewLines.count,
    showAvatars = settings.showAvatars,
    unreadColor = when {
        dynamicInUse -> primary
        dark -> IosBlueDark
        else -> IosBlueLight
    }
)

/** The appearance of the rows of the current theme; the defaults of [AppSettings] outside one. */
val LocalRowAppearance = compositionLocalOf {
    RowAppearance(PreviewLines.TWO.count, showAvatars = false, unreadColor = IosBlueLight)
}
