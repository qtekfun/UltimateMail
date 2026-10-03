// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.ui.account.AddAccountViewModel
import com.qtekfun.ultimatemail.ui.conversation.ConversationViewModel
import com.qtekfun.ultimatemail.ui.drawer.DrawerViewModel
import com.qtekfun.ultimatemail.ui.inbox.InboxViewModel
import com.qtekfun.ultimatemail.ui.nav.AppNavigator
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appSettings by settings.state.collectAsStateWithLifecycle()
            UltimateMailTheme(appSettings.settings) {
                AppRoot(navigator, drawer, addAccount, inbox, conversation, settings, accountSettings)
            }
        }
    }
}
