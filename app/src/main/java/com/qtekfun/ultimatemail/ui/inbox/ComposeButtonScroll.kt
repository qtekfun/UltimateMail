// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.qtekfun.ultimatemail.domain.inbox.FabScrollTracker
import com.qtekfun.ultimatemail.ui.components.rememberReduceMotion
import com.qtekfun.ultimatemail.ui.components.rememberTouchExploration

/**
 * Reports whether the Compose button should be hidden for the way [listState] scrolls (see
 * [FabScrollTracker]). With a screen reader on it never hides: it must stay reachable.
 */
@Composable
internal fun TrackFabVisibility(listState: LazyListState, onHidden: (Boolean) -> Unit) {
    val screenReader = rememberTouchExploration()
    val report by rememberUpdatedState(onHidden)
    LaunchedEffect(listState, screenReader) {
        if (screenReader) {
            report(false)
            return@LaunchedEffect
        }
        val tracker = FabScrollTracker()
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (index, offset, scrolling) ->
            report(tracker.onScroll(index, offset, scrolling))
        }
    }
}

/** The button with a fade and scale when it comes and goes; no motion if animations are off. */
@Composable
internal fun ScrollAwareButton(visible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else scaleIn() + fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else scaleOut() + fadeOut()
    ) { content() }
}
