// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons the core Material icon set does not have, drawn from Material Symbols path data
 * (Apache-2.0, compatible with GPL-3.0) so the project needs no extended icons dependency.
 */
object MailIcons {
    private const val SIZE = 24f

    private const val ATTACHMENT_PATH =
        "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5" +
            "s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5" +
            "c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4" +
            "S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z"

    /** A paperclip: the message has attachments. */
    val Attachment: ImageVector by lazy {
        ImageVector.Builder(
            name = "Attachment",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = SIZE,
            viewportHeight = SIZE
        ).addPath(
            pathData = PathParser().parsePathString(ATTACHMENT_PATH).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()
    }
}
