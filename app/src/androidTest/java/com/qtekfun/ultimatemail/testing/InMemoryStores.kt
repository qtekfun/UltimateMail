// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.data.settings.PreferenceStore
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.CredentialVault
import com.qtekfun.ultimatemail.domain.oauth.OAuthClientIds
import com.qtekfun.ultimatemail.domain.picker.RecentDestinations
import com.qtekfun.ultimatemail.domain.picker.RecentLog
import com.qtekfun.ultimatemail.domain.search.RecentSearchLog
import com.qtekfun.ultimatemail.domain.search.RecentSearches
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Account secrets in memory: nothing is encrypted or written, and nothing outlives the test. */
@Singleton
class InMemoryVault @Inject constructor() : CredentialVault {
    private val stored = ConcurrentHashMap<Long, AccountCredentials>()

    override suspend fun save(accountId: Long, credentials: AccountCredentials) {
        stored[accountId] = credentials
    }

    override suspend fun load(accountId: Long): AccountCredentials? = stored[accountId]

    override suspend fun delete(accountId: Long) {
        stored.remove(accountId)
    }
}

/** The app settings in memory, announcing changes like SharedPreferences does. */
@Singleton
class InMemoryPreferenceStore @Inject constructor() : PreferenceStore {
    private val values = ConcurrentHashMap<String, Any>()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = CHANGE_BUFFER)

    override fun getString(key: String): String? = values[key] as? String

    override fun getBoolean(key: String, default: Boolean): Boolean =
        values[key] as? Boolean ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
        changed.tryEmit(Unit)
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
        changed.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changed

    private companion object {
        const val CHANGE_BUFFER = 16
    }
}

/** The destinations the move picker remembers, in memory. */
@Singleton
class InMemoryRecentDestinations @Inject constructor() : RecentDestinations {
    private val logs = ConcurrentHashMap<Long, RecentLog>()

    override fun recent(accountId: Long): List<String> = log(accountId).recent()

    override fun usage(accountId: Long): Map<String, Int> = log(accountId).usage()

    override fun record(accountId: Long, paths: List<String>) {
        logs[accountId] = log(accountId).recorded(paths)
    }

    private fun log(accountId: Long) = logs[accountId] ?: RecentLog.Empty
}

/** The searches the search screen remembers, in memory. */
@Singleton
class InMemoryRecentSearches @Inject constructor() : RecentSearches {
    @Volatile
    private var log = RecentSearchLog.EMPTY

    override fun recent(): List<String> = log.recent()

    override fun record(text: String) {
        log = log.recorded(text)
    }

    override fun remove(text: String) {
        log = log.removed(text)
    }

    override fun clear() {
        log = RecentSearchLog.EMPTY
    }
}

/** The OAuth client IDs the user typed, in memory. */
@Singleton
class InMemoryClientIds @Inject constructor() : OAuthClientIds {
    @Volatile
    private var google: String? = null

    @Volatile
    private var microsoft: String? = null

    override fun google(): String? = google

    override fun microsoft(): String? = microsoft

    override fun setGoogle(clientId: String) {
        google = clientId.ifEmpty { null }
    }

    override fun setMicrosoft(clientId: String) {
        microsoft = clientId.ifEmpty { null }
    }
}
