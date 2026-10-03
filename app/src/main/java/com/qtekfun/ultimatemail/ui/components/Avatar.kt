// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatemail.domain.inbox.AvatarSpec
import com.qtekfun.ultimatemail.ui.theme.AvatarPalette

/** Default sizes of [Avatar]. */
object AvatarDefaults {
    val Size: Dp = 40.dp
}

/**
 * The circle with the sender's initial. The initial comes from [name] (or [address] when the
 * name is empty) and the colour only from [address], so a person looks the same everywhere
 * (see [AvatarSpec]). It is decorative for screen readers unless a [contentDescription] is
 * given, because the row or screen that shows it already says who the sender is.
 */
@Composable
fun Avatar(
    name: String,
    address: String,
    modifier: Modifier = Modifier,
    size: Dp = AvatarDefaults.Size,
    contentDescription: String? = null
) {
    val spec = remember(name, address) { AvatarSpec.of(name, address) }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Box(
        modifier = modifier
            .then(semanticsModifier)
            .size(size)
            .clip(CircleShape)
            .background(AvatarPalette[spec.colorIndex]),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = spec.initial,
            color = Color.White,
            // Sized from the circle, not from the font scale: the circle does not grow with it.
            fontSize = (size.value * INITIAL_RATIO).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private const val INITIAL_RATIO = 0.45f
