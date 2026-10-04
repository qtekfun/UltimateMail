// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.ui.drawer.displayName
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

private val MinTouchTarget = 48.dp

/** Height of the large bar when it is expanded at the default font size. */
private val ExpandedHeight = 152.dp

/** What the title of the list says: the mailbox, and a line under it. */
internal class TitleInfo(val state: InboxState?, val syncLine: StateFlow<SyncLine>)

/**
 * The large title bar of the list (iOS Mail style): the menu button on the left, "Edit" on the
 * right, the mailbox name big below them. It collapses to a normal bar when the list scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxTopBar(
    scope: InboxScope,
    info: TitleInfo,
    actions: InboxActions,
    scrollBehavior: TopAppBarScrollBehavior
) {
    // The expanded height is in dp, so it has to grow with a large font for the two lines to fit.
    val expanded = ExpandedHeight * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LargeTopAppBar(
        title = { InboxTitle(scope, info, scrollBehavior) },
        navigationIcon = {
            IconButton(
                onClick = actions.onOpenMenu,
                modifier = Modifier.heightIn(min = MinTouchTarget)
            ) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.drawer_open))
            }
        },
        actions = {
            if (info.state != null) {
                BarTextButton(stringResource(R.string.inbox_edit), actions.selection.onEdit)
            }
        },
        expandedHeight = expanded,
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxTitle(
    scope: InboxScope,
    info: TitleInfo,
    scrollBehavior: TopAppBarScrollBehavior
) {
    val state = info.state
    val title = when (scope) {
        InboxScope.Unified -> stringResource(R.string.inbox_unified)

        is InboxScope.Folder ->
            state?.folderRole?.displayName() ?: state?.folderName
                ?: stringResource(R.string.app_name)
    }
    Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state != null) {
            // Fades out while the bar collapses; read in the layer, so scrolling only redraws it.
            Subtitle(
                info,
                Modifier.graphicsLayer {
                    alpha = (1f - scrollBehavior.state.collapsedFraction * 2f).coerceIn(0f, 1f)
                }
            )
        }
    }
}

@Composable
private fun Subtitle(info: TitleInfo, modifier: Modifier) {
    val line by info.syncLine.collectAsStateWithLifecycle()
    val filter = info.state?.filter ?: InboxFilter.ALL
    val text = if (filter != InboxFilter.ALL) {
        stringResource(R.string.inbox_filtered_by, stringResource(filter.labelRes()))
    } else {
        line.subtitle()
    }
    if (text != null) {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The text under the title for the sync [SyncLine], or null when there is nothing to say. */
@Composable
private fun SyncLine.subtitle(): String? = when (this) {
    SyncLine.NeverSynced -> null

    SyncLine.Syncing -> stringResource(R.string.sync_status_syncing)

    is SyncLine.SyncingFolders -> stringResource(R.string.sync_status_folders, done, total)

    is SyncLine.DownloadingMessages ->
        stringResource(R.string.sync_status_downloading, done, total)

    is SyncLine.LastSynced -> stringResource(
        R.string.inbox_updated,
        DateFormat.getTimeFormat(LocalContext.current).format(Date.from(at))
    )

    SyncLine.SignInAgain -> stringResource(R.string.sync_status_reauth)

    is SyncLine.Failed -> stringResource(
        when (problem) {
            SyncProblem.NETWORK -> R.string.sync_error_network
            SyncProblem.TIMEOUT -> R.string.sync_error_timeout
            SyncProblem.CERTIFICATE -> R.string.sync_error_certificate
            SyncProblem.SERVER, SyncProblem.PROTOCOL -> R.string.sync_error_server
            SyncProblem.UNKNOWN -> R.string.sync_error_unknown
        }
    )
}
