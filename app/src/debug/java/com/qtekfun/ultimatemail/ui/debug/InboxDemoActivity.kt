// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.FileAttachmentStorage
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.di.AttachmentModule
import com.qtekfun.ultimatemail.ui.theme.UltimateMailTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only: fills Room with made-up accounts and conversations so the inbox can be
 * seen without the sync engine, and removes them again. It only ever creates or deletes accounts
 * of the reserved demo domain (see [DemoData]). The `action` extra (`seed` or `remove`) does it
 * without touching the screen, so it can be started from adb.
 */
@AndroidEntryPoint
class InboxDemoActivity : ComponentActivity() {
    @Inject lateinit var database: UltimateMailDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_SEED -> launchAction { seed() }
            ACTION_REMOVE -> launchAction { remove() }
        }
        setContent {
            UltimateMailTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoScreen(onSeed = {
                        launchAction { seed() }
                    }, onRemove = { launchAction { remove() } })
                }
            }
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { action() }
            if (intent.getStringExtra(EXTRA_ACTION) != null) finish()
        }
    }

    private suspend fun seed() {
        remove()
        val now = Instant.now()
        DemoData.accounts().forEachIndexed { index, account ->
            val id = database.accountDao().insert(account)
            database.folderDao().upsert(DemoData.folders(id))
            val count = if (index == 0) FIRST_COUNT else SECOND_COUNT
            database.messageDao().upsert(DemoData.inboxMessages(id, now, count, index))
            seedBodies(id, count)
        }
    }

    /**
     * Gives the first conversations and the 12-message thread a body (and attachments), so the
     * reading screen can be tried. Downloaded demo attachments are written like real ones.
     */
    private suspend fun seedBodies(accountId: Long, count: Int) {
        val storage = FileAttachmentStorage(File(filesDir, AttachmentModule.FOLDER))
        for (index in 0 until count) {
            DemoBodies.forListMessage(index)?.let { saveBody(accountId, index + 1L, it, storage) }
        }
        for (k in 0 until DemoData.THREAD_SIZE) {
            val body = DemoBodies.forThreadMessage(k, last = k == DemoData.THREAD_SIZE - 1)
            saveBody(accountId, count + 1L + k, body, storage)
        }
    }

    private suspend fun saveBody(
        accountId: Long,
        uid: Long,
        body: DemoBody,
        storage: FileAttachmentStorage
    ) {
        val messages = database.messageDao()
        messages.setBody(accountId, "INBOX", uid, body.text, body.html)
        val messageId = messages.get(accountId, "INBOX", uid)?.id ?: return
        val attachments = database.attachmentDao()
        attachments.insert(
            body.attachments.mapIndexed { position, file ->
                AttachmentEntity(
                    messageId = messageId,
                    partId = "${position + 2}",
                    fileName = file.name,
                    mimeType = file.mimeType,
                    size = file.size,
                    contentId = file.contentId,
                    inline = file.inline
                )
            }
        )
        attachments.listFor(messageId).zip(body.attachments).forEach { (row, file) ->
            file.bytes?.let {
                attachments.setState(
                    row.id,
                    AttachmentState.DOWNLOADED,
                    storage.write(accountId, row, it)
                )
            }
        }
    }

    private suspend fun remove() {
        val storage = FileAttachmentStorage(File(filesDir, AttachmentModule.FOLDER))
        database.accountDao().observeAll().first()
            .filter { DemoData.isDemo(it.email) }
            .forEach {
                database.accountDao().delete(it.id)
                storage.deleteAccount(it.id)
            }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_SEED = "seed"
        const val ACTION_REMOVE = "remove"
        private const val FIRST_COUNT = 70
        private const val SECOND_COUNT = 15
    }
}

@Composable
private fun DemoScreen(onSeed: () -> Unit, onRemove: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.debug_demo_explanation))
        Button(onClick = {
            onSeed()
            done = true
        }) { Text(stringResource(R.string.debug_demo_seed)) }
        OutlinedButton(onClick = {
            onRemove()
            done = true
        }) { Text(stringResource(R.string.debug_demo_remove)) }
        if (done) Text(stringResource(R.string.debug_demo_done))
    }
}
