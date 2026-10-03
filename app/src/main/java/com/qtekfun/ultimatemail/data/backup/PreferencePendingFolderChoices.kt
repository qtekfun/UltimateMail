// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.backup

import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import com.qtekfun.ultimatemail.domain.backup.BackupJson
import com.qtekfun.ultimatemail.domain.backup.JsonFormatException
import com.qtekfun.ultimatemail.domain.backup.JsonValue
import com.qtekfun.ultimatemail.domain.backup.PendingFolderChoices
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PendingFolderChoices] kept in a [PreferenceStore] as a small JSON object per account (path to
 * synced), so importing needs no schema change. An unreadable value counts as "nothing waits".
 */
@Singleton
class PreferencePendingFolderChoices @Inject constructor(private val store: PreferenceStore) :
    PendingFolderChoices {
    override fun save(accountId: Long, choices: Map<String, Boolean>) {
        val json = JsonValue.Obj(choices.mapValues { JsonValue.Bool(it.value) })
        store.putString(key(accountId), BackupJson.write(json))
    }

    override fun peek(accountId: Long): Map<String, Boolean> {
        val text = store.getString(key(accountId)).orEmpty()
        if (text.isEmpty()) return emptyMap()
        return try {
            val fields = (BackupJson.parse(text) as? JsonValue.Obj)?.fields.orEmpty()
            fields.mapNotNull { (path, value) ->
                (value as? JsonValue.Bool)?.let { path to it.value }
            }
                .toMap()
        } catch (_: JsonFormatException) {
            emptyMap()
        }
    }

    override fun clear(accountId: Long) = store.putString(key(accountId), "")

    private fun key(accountId: Long) = "pending_folder_sync_$accountId"
}
