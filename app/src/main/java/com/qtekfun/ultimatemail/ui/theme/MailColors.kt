// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours of sender avatars and account markers. Every one has at least 4.5:1 contrast with
 * white, the colour of the initial. The size must match `AvatarSpec.PALETTE_SIZE`.
 */
internal val AvatarPalette = listOf(
    Color(0xFF1565C0),
    Color(0xFF00796B),
    Color(0xFF2E7D32),
    Color(0xFFB23C00),
    Color(0xFF6A1B9A),
    Color(0xFFAD1457),
    Color(0xFF3949AB),
    Color(0xFF6D4C41),
    Color(0xFFC62828),
    Color(0xFF455A64)
)

/** Label chips on a light surface: pastel background, dark text. */
internal val LabelPaletteLight = listOf(
    ChipColors(Color(0xFFD6E4FF), Color(0xFF0B3A8C)),
    ChipColors(Color(0xFFCDEFE3), Color(0xFF0B4F3A)),
    ChipColors(Color(0xFFFFE0B2), Color(0xFF6A3500)),
    ChipColors(Color(0xFFF3D9FA), Color(0xFF5B1A72)),
    ChipColors(Color(0xFFFFD9E1), Color(0xFF7A1033)),
    ChipColors(Color(0xFFE3EBB8), Color(0xFF3F4A00)),
    ChipColors(Color(0xFFD3E8F2), Color(0xFF0D4A63)),
    ChipColors(Color(0xFFE6DDD6), Color(0xFF4A3426))
)

/** Label chips on a dark surface: deep background, light text. */
internal val LabelPaletteDark = listOf(
    ChipColors(Color(0xFF1E3A66), Color(0xFFD6E4FF)),
    ChipColors(Color(0xFF14503E), Color(0xFFCDEFE3)),
    ChipColors(Color(0xFF5E3A08), Color(0xFFFFE0B2)),
    ChipColors(Color(0xFF4B2358), Color(0xFFF3D9FA)),
    ChipColors(Color(0xFF6B1C34), Color(0xFFFFD9E1)),
    ChipColors(Color(0xFF3D4511), Color(0xFFE3EBB8)),
    ChipColors(Color(0xFF14465A), Color(0xFFD3E8F2)),
    ChipColors(Color(0xFF45352B), Color(0xFFE6DDD6))
)

/** The colour of the "starred" icon. */
internal val StarColor = Color(0xFFF2A600)
