// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.ConversationSummary
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.domain.picker.MessageRef
import com.qtekfun.ultimatemail.domain.picker.MoveLabelActions
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.ui.picker.MoveLabelDialog
import com.qtekfun.ultimatemail.ui.picker.message
import com.qtekfun.ultimatemail.ui.theme.UltimateMailTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only: opens the move / label picker over made-up accounts ([PickerDemoData]) so it
 * can be tried on a device. It works on accounts of the reserved demo domain only: the screen
 * lists nothing else, so the picker is never given a real account. Moves and labels go through
 * the real queue and local state; with no credentials they simply stay pending.
 */
@AndroidEntryPoint
class PickerDemoActivity : ComponentActivity() {
    @Inject lateinit var database: UltimateMailDatabase

    @Inject lateinit var actions: MoveLabelActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UltimateMailTheme {
                DemoScreen(
                    database = database,
                    actions = actions,
                    onSeed = { launchIo { seed() } },
                    onRemove = { launchIo { remove() } }
                )
            }
        }
    }

    private fun launchIo(action: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { action() } }
    }

    private suspend fun seed() {
        remove()
        val now = Instant.now()
        listOf(false to PickerDemoData.foldersAccount(), true to PickerDemoData.labelsAccount())
            .forEach { (labels, account) ->
                val id = database.accountDao().insert(account)
                database.folderDao().upsert(PickerDemoData.folders(id, labels))
                database.messageDao().upsert(PickerDemoData.messages(id, labels, now))
            }
    }

    private suspend fun remove() {
        database.accountDao().observeAll().first()
            .filter { PickerDemoData.isPickerDemo(it.email) }
            .forEach { database.accountDao().delete(it.id) }
    }
}

@Composable
private fun DemoScreen(
    database: UltimateMailDatabase,
    actions: MoveLabelActions,
    onSeed: () -> Unit,
    onRemove: () -> Unit
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val accounts by remember {
        database.accountDao().observeAll().map { list ->
            list.filter { PickerDemoData.isPickerDemo(it.email) }
        }
    }.collectAsState(initial = emptyList())
    var labelsAccount by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull {
        (it.email == PickerDemoData.LABELS_EMAIL) == labelsAccount
    }
    val conversations by remember(account?.id) { conversationsOf(database, account) }
        .collectAsState(initial = emptyList())
    var selected by remember(account?.id) { mutableStateOf(emptySet<Long>()) }
    var picking by remember { mutableStateOf<PickerRequest?>(null) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.debug_picker_explanation))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSeed) { Text(stringResource(R.string.debug_demo_seed)) }
                OutlinedButton(onClick = onRemove) {
                    Text(stringResource(R.string.debug_demo_remove))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !labelsAccount,
                    onClick = { labelsAccount = false },
                    label = { Text(stringResource(R.string.debug_picker_folders_account)) }
                )
                FilterChip(
                    selected = labelsAccount,
                    onClick = { labelsAccount = true },
                    label = { Text(stringResource(R.string.debug_picker_labels_account)) }
                )
            }
            Button(
                enabled = account != null && selected.isNotEmpty(),
                onClick = {
                    account?.let {
                        picking =
                            PickerRequest(it.id, selected.map { uid -> MessageRef("INBOX", uid) })
                    }
                }
            ) { Text(stringResource(R.string.debug_picker_open)) }
            LazyColumn(Modifier.weight(1f)) {
                items(conversations, key = { it.latest.id }) { row ->
                    DemoRow(row, row.latest.uid in selected) { on ->
                        selected = if (on) selected + row.latest.uid else selected - row.latest.uid
                    }
                }
            }
        }
    }

    picking?.let { request ->
        MoveLabelDialog(
            request = request,
            onDismiss = { picking = null },
            onApplied = { result ->
                selected = emptySet()
                scope.launch {
                    val answer = snackbar.showSnackbar(
                        message = result.message(resources),
                        actionLabel = resources.getString(R.string.picker_undo),
                        duration = SnackbarDuration.Long
                    )
                    if (answer == SnackbarResult.ActionPerformed) actions.undo(result)
                }
            }
        )
    }
}

private fun conversationsOf(
    database: UltimateMailDatabase,
    account: AccountEntity?
): Flow<List<ConversationSummary>> = if (account == null) {
    emptyFlow()
} else {
    database.conversationDao().observeConversations(account.id, "INBOX", DEMO_LIMIT)
}

@Composable
private fun DemoRow(row: ConversationSummary, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(row.latest.subject)
            Text(row.latest.labels.joinToString(", "))
            if (row.latest.pendingSync) Text(stringResource(R.string.debug_picker_pending))
        }
    }
}

private const val DEMO_LIMIT = 50
