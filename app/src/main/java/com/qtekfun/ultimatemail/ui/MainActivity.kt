// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.compose.ComposeEntryViewModel
import com.qtekfun.ultimatemail.ui.compose.ComposeScreens
import com.qtekfun.ultimatemail.ui.compose.ComposeStart
import com.qtekfun.ultimatemail.ui.compose.ComposerViewModel
import com.qtekfun.ultimatemail.ui.compose.DraftsViewModel
import com.qtekfun.ultimatemail.ui.compose.OutboxViewModel
import com.qtekfun.ultimatemail.ui.compose.toIncomingCompose
import com.qtekfun.ultimatemail.ui.conversation.ConversationViewModel
import com.qtekfun.ultimatemail.ui.drawer.DrawerViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
import com.qtekfun.ultimatemail.ui.search.SearchViewModel
import com.qtekfun.ultimatemail.ui.settings.AccountSettingsViewModel
import com.qtekfun.ultimatemail.ui.settings.SettingsViewModel
import com.qtekfun.ultimatemail.ui.theme.UltimateMailTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Activity-scoped view models: hilt-navigation-compose is not a dependency of the project.
    private val navigator: AppNavigator by viewModels()
    private val drawer: DrawerViewModel by viewModels()
    private val addAccount: AddAccountViewModel by viewModels()
    private val inbox: InboxViewModel by viewModels()
    private val conversation: ConversationViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val accountSettings: AccountSettingsViewModel by viewModels()
    private val composeEntry: ComposeEntryViewModel by viewModels()
    private val composer: ComposerViewModel by viewModels()
    private val drafts: DraftsViewModel by viewModels()
    private val outbox: OutboxViewModel by viewModels()
    private val search: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A rotation recreates the activity with the same intent: handle it only the first time.
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val appSettings by settings.state.collectAsStateWithLifecycle()
            UltimateMailTheme(appSettings.settings) {
                AppRoot(
                    navigator,
                    drawer,
                    addAccount,
                    inbox,
                    conversation,
                    settings,
                    accountSettings,
                    ComposeScreens(composeEntry, composer, drafts, outbox),
                    search
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** A mailto link or a share from another app opens the composer; anything else is ignored. */
    private fun handleIncoming(intent: Intent?) {
        val incoming = intent?.toIncomingCompose("$packageName.files") ?: return
        composeEntry.start(ComposeStart.Incoming(incoming))
    }
}
