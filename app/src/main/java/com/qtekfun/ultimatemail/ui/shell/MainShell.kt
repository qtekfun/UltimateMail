// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.domain.inbox.InboxScope
import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.drawer.DrawerContent
import com.qtekfun.ultimatemail.ui.drawer.FolderMenuState
import com.qtekfun.ultimatemail.ui.inbox.InboxActions
import com.qtekfun.ultimatemail.ui.inbox.InboxScreen
import com.qtekfun.ultimatemail.ui.inbox.InboxState

/**
 * The main screen: the side menu with the folders and, as the body, the conversation list of
 * [scope]. [menuOpen] is the single source of truth for whether the menu is open (it lives in
 * `AppNavigator`, so Back and saved state work); the Material drawer follows it and reports
 * swipes back through [onMenuOpenChange].
 *
 * This is the base for the next screens: a later task shows its own body here instead of the
 * conversation list, or opens a full-screen `Screen` of its own.
 */
@Composable
fun MainShell(
    scope: InboxScope,
    menu: FolderMenuState,
    menuOpen: Boolean,
    inboxState: InboxState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
    compose: ShellCompose = ShellCompose()
) {
    val drawerState = rememberDrawerState(if (menuOpen) DrawerValue.Open else DrawerValue.Closed)
    LaunchedEffect(menuOpen) {
        if (menuOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow {
            drawerState.currentValue
        }.collect { actions.onMenuOpenChange(it == DrawerValue.Open) }
    }
    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(menu, scope, actions.drawer, outboxCount = compose.outboxCount)
        }
    ) {
        val drafts = compose.drafts
        if (drafts != null) {
            drafts()
        } else {
            InboxScreen(
                scope = scope,
                state = inboxState,
                actions = actions.inbox,
                floatingActionButton = { ComposeButton(compose.onCompose) }
            )
        }
    }
}

/** The "Compose" floating button: an icon and its name, so it is clear without a long press. */
@Composable
private fun ComposeButton(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        text = { Text(stringResource(R.string.compose_fab)) },
        modifier = Modifier.heightIn(min = 56.dp)
    )
}

/** Shown instead of the shell when there is no account: there is nothing to put in the menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoAccountsScreen(onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.home_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onAddAccount, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.account_add))
            }
        }
    }
}
