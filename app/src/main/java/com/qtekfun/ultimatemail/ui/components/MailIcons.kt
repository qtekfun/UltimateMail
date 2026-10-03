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

    private const val INBOX_PATH =
        "M19,3H4.99c-1.11,0 -1.98,0.89 -1.98,2L3,19c0,1.1 0.88,2 1.99,2H19c1.1,0 2,-0.9 2,-2V5" +
            "c0,-1.11 -0.9,-2 -2,-2zM19,15h-4c0,1.66 -1.35,3 -3,3s-3,-1.34 -3,-3H4.99V5H19v10z"

    private const val ARCHIVE_PATH =
        "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55" +
            "L3.46,5.23C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5" +
            "c0,-0.48 -0.17,-0.93 -0.46,-1.27zM12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5z" +
            "M5.12,5l0.81,-1h12l0.94,1H5.12z"

    private const val FOLDER_PATH =
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8" +
            "c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"

    private const val LABEL_PATH =
        "M17.63,5.84C17.27,5.33 16.67,5 16,5L5,5.01C3.9,5.01 3,5.9 3,7v10c0,1.1 0.9,1.99 2,1.99" +
            "L16,19c0.67,0 1.27,-0.33 1.63,-0.84L22,12l-4.37,-6.16z"

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = SIZE,
        viewportHeight = SIZE
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()

    /** An inbox tray. */
    val Inbox: ImageVector by lazy { icon("Inbox", INBOX_PATH) }

    /** A box with a down arrow: archived mail. */
    val Archive: ImageVector by lazy { icon("Archive", ARCHIVE_PATH) }

    /** A plain folder. */
    val Folder: ImageVector by lazy { icon("Folder", FOLDER_PATH) }

    /** A tag: a Gmail label. */
    val Label: ImageVector by lazy { icon("Label", LABEL_PATH) }

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
