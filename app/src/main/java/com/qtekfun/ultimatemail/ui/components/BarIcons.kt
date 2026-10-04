// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons of the floating bar of the message list, drawn here from simple shapes (no outside
 * assets): a circle with three shrinking lines for the filter, and a sheet with a pencil to
 * compose. Even-odd fill, so the lines and the pencil cut or fill as the shapes overlap.
 */
object BarIcons {
    private const val SIZE = 24f

    private const val LINES = "M7,8.5h10v1.6H7z M9,11.2h6v1.6H9z M11,13.9h2v1.6h-2z"
    private const val DISC = "M12,2a10,10 0 1,0 0.001,0z"
    private const val RING_HOLE = "M12,3.4a8.6,8.6 0 1,0 0.001,0z"

    private const val SHEET =
        "M5,3h8l-2,2H5v14h14v-6l2,-2v8c0,1.1 -0.9,2 -2,2H5c-1.1,0 -2,-0.9 -2,-2V5" +
            "c0,-1.1 0.9,-2 2,-2z"
    private const val PENCIL = "M18.4,2.6l3,3L13,14l-4,1l1,-4z"

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = SIZE.dp,
        defaultHeight = SIZE.dp,
        viewportWidth = SIZE,
        viewportHeight = SIZE
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black)
    ).build()

    /** The filter when nothing is filtered: a ring with the three lines. */
    val Filter: ImageVector by lazy { icon("Filter", "$DISC $RING_HOLE $LINES") }

    /** The filter while a filter is on: the same, filled. */
    val FilterActive: ImageVector by lazy { icon("FilterActive", "$DISC $LINES") }

    /** A sheet with a pencil: write a new message. */
    val Compose: ImageVector by lazy { icon("Compose", "$SHEET $PENCIL") }
}
