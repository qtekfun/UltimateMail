// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.folder.FolderListItem
import com.qtekfun.ultimatemail.domain.folder.SyncLine
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.ui.nav.Screen
import com.qtekfun.ultimatemail.ui.theme.LocalDensityMetrics
import java.util.Date

private val MinTouchTarget = 48.dp

/** The sync status line with its refresh button; "sign in again" is a button that opens re-auth. */
@Composable
private fun SyncStatusRow(syncLine: SyncLine, accountId: Long, actions: DrawerActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (syncLine.needsSignIn) {
            // The line is the way out: tapping it opens the sign-in-again screen.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .clickable(
                        onClickLabel = stringResource(R.string.drawer_reauth_action),
                        role = Role.Button,
                        onClick = { actions.onReauthenticate(accountId) }
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = syncLine.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Text(
                text = syncLine.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        IconButton(
            onClick = actions.onRefresh,
            modifier = Modifier.heightIn(min = MinTouchTarget)
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.drawer_sync_now)
            )
        }
    }
}

@Composable
internal fun DrawerFooter(
    syncLine: SyncLine,
    accountId: Long,
    actions: DrawerActions,
    outboxCount: Int
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        SyncStatusRow(syncLine, accountId, actions)
        if (outboxCount > 0) {
            DrawerRow(
                label = stringResource(R.string.drawer_outbox),
                icon = Icons.AutoMirrored.Filled.Send,
                selected = false,
                onClick = { actions.onOpenDestination(Screen.Outbox) },
                unread = outboxCount,
                countDescription = pluralStringResource(
                    R.plurals.drawer_outbox_count,
                    outboxCount,
                    outboxCount
                )
            )
        }
        DrawerDestinations.footer.forEach { destination ->
            DrawerRow(
                label = stringResource(destination.label),
                icon = destination.icon,
                selected = false,
                onClick = { actions.onOpenDestination(destination.screen) }
            )
        }
    }
}

@Composable
private fun SyncLine.text(): String = when (this) {
    SyncLine.NeverSynced -> stringResource(R.string.sync_status_never)

    SyncLine.Syncing -> stringResource(R.string.sync_status_syncing)

    is SyncLine.SyncingFolders -> stringResource(R.string.sync_status_folders, done, total)

    is SyncLine.DownloadingMessages ->
        stringResource(R.string.sync_status_downloading, done, total)

    is SyncLine.LastSynced -> {
        val context = LocalContext.current
        stringResource(
            R.string.sync_status_last,
            DateFormat.getTimeFormat(context).format(Date.from(at))
        )
    }

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
